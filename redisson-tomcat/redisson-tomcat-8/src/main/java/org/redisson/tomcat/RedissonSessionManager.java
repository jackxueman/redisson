/**
 * Copyright 2018 Nikita Koksharov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.redisson.tomcat;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.Map.Entry;

import javax.servlet.http.HttpSession;

import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleState;
import org.apache.catalina.Session;
import org.apache.catalina.session.ManagerBase;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.redisson.Redisson;
import org.redisson.api.RMap;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.api.listener.MessageListener;
import org.redisson.client.codec.Codec;
import org.redisson.config.Config;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

/**
 * Redisson Session Manager for Apache Tomcat
 * 
 * @author Nikita Koksharov
 *
 */
public class RedissonSessionManager extends ManagerBase {

    public enum ReadMode {REDIS, MEMORY}
    public enum UpdateMode {DEFAULT, AFTER_REQUEST}
    
    private final Log log = LogFactory.getLog(RedissonSessionManager.class);
    
    private RedissonClient redisson;
    private String configPath;
    
    private ReadMode readMode = ReadMode.MEMORY;
    private UpdateMode updateMode = UpdateMode.DEFAULT;

    private String keyPrefix = "";
    
    public String getUpdateMode() {
        return updateMode.toString();
    }

    public void setUpdateMode(String updateMode) {
        this.updateMode = UpdateMode.valueOf(updateMode);
    }

    public String getReadMode() {
        return readMode.toString();
    }

    public void setReadMode(String readMode) {
        this.readMode = ReadMode.valueOf(readMode);
    }
    
    public void setConfigPath(String configPath) {
        this.configPath = configPath;
    }
    
    public String getConfigPath() {
        return configPath;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }

    @Override
    public String getName() {
        return RedissonSessionManager.class.getSimpleName();
    }
    
    @Override
    public void load() throws ClassNotFoundException, IOException {
    }

    @Override
    public void unload() throws IOException {
    }

    @Override
    public Session createSession(String sessionId) {
        RedissonSession session = (RedissonSession) createEmptySession();
        
        session.setNew(true);
        session.setValid(true);
        session.setCreationTime(System.currentTimeMillis());
        session.setMaxInactiveInterval(getContext().getSessionTimeout() * 60);

        if (sessionId == null) {
            sessionId = generateSessionId();
        }
        
        session.setId(sessionId);
        session.save();
        
        return session;
    }

    public RMap<String, Object> getMap(String sessionId) {
        String separator = keyPrefix == null || keyPrefix.isEmpty() ? "" : ":";
        final String name = keyPrefix + separator + "redisson:tomcat_session:" + sessionId;
        return redisson.getMap(name);
    }
    
    public RTopic<AttributeMessage> getTopic() {
        return redisson.getTopic("redisson:tomcat_session_updates");
    }
    
    @Override
    public Session findSession(String id) throws IOException {
        Session result = super.findSession(id);
        if (result == null && id != null) {
            Map<String, Object> attrs = getMap(id).readAllMap();
            
            if (attrs.isEmpty() || !Boolean.valueOf(String.valueOf(attrs.get("session:isValid")))) {
                log.info("Session " + id + " can't be found");
                return null;
            }
            
            RedissonSession session = (RedissonSession) createEmptySession();
            session.setId(id);
            session.load(attrs);
            
            session.access();
            session.endAccess();
            return session;
        }

        result.access();
        result.endAccess();
        
        return result;
    }
    
    @Override
    public Session createEmptySession() {
        return new RedissonSession(this, readMode, updateMode);
    }
    
    @Override
    public void remove(Session session, boolean update) {
        super.remove(session, update);
        
        if (session.getIdInternal() != null) {
            ((RedissonSession)session).delete();
        }
    }
    
    protected Object decode(byte[] bb) throws IOException {
        ByteBuf buf = ByteBufAllocator.DEFAULT.buffer(bb.length);
        buf.writeBytes(bb);
        Object value = redisson.getConfig().getCodec().getValueDecoder().decode(buf, null);
        buf.release();
        return value;
    }
    
    public byte[] encode(Object value) {
        try {
            ByteBuf encoded = redisson.getConfig().getCodec().getValueEncoder().encode(value);
            byte[] dst = new byte[encoded.readableBytes()];
            encoded.readBytes(dst);
            encoded.release();
            return dst;
        } catch (IOException e) {
            log.error("Unable to encode object", e);
            throw new IllegalStateException(e);
        }
    }
    
    public RedissonClient getRedisson() {
        return redisson;
    }
    
    @Override
    protected void startInternal() throws LifecycleException {
        super.startInternal();
        redisson = buildClient();
        
        if (updateMode == UpdateMode.AFTER_REQUEST) {
            getEngine().getPipeline().addValve(new UpdateValve(this));
        }

        if (readMode == ReadMode.MEMORY) {
            RTopic<AttributeMessage> updatesTopic = getTopic();
            updatesTopic.addListener(new MessageListener<AttributeMessage>() {
                
                @Override
                public void onMessage(String channel, AttributeMessage msg) {
                    try {
                        // TODO make it thread-safe
                        RedissonSession session = (RedissonSession) RedissonSessionManager.super.findSession(msg.getSessionId());
                        if (session != null) {
                            if (msg instanceof AttributeRemoveMessage) {
                                session.superRemoveAttributeInternal(((AttributeRemoveMessage)msg).getName(), true);
                            }

                            if (msg instanceof AttributesClearMessage) {
                                RedissonSessionManager.super.remove(session, false);
                            }
                            
                            if (msg instanceof AttributesPutAllMessage) {
                                AttributesPutAllMessage m = (AttributesPutAllMessage) msg;
                                for (Entry<String, byte[]> entry : m.getAttrs().entrySet()) {
                                    session.superSetAttribute(entry.getKey(), decode(entry.getValue()), true);
                                }
                            }
                            
                            if (msg instanceof AttributeUpdateMessage) {
                                AttributeUpdateMessage m = (AttributeUpdateMessage)msg;
                                session.superSetAttribute(m.getName(), decode(m.getValue()), true);
                            }
                        }
                    } catch (IOException e) {
                        log.error("Can't handle topic message", e);
                    }
                }
            });
        }
        
        setState(LifecycleState.STARTING);
    }

    protected RedissonClient buildClient() throws LifecycleException {
        Config config = null;
        try {
            config = Config.fromJSON(new File(configPath), getClass().getClassLoader());
        } catch (IOException e) {
            // trying next format
            try {
                config = Config.fromYAML(new File(configPath), getClass().getClassLoader());
            } catch (IOException e1) {
                log.error("Can't parse json config " + configPath, e);
                throw new LifecycleException("Can't parse yaml config " + configPath, e1);
            }
        }
        
        try {
            try {
            Config c = new Config(config);
            Codec codec = c.getCodec().getClass().getConstructor(ClassLoader.class)
                            .newInstance(Thread.currentThread().getContextClassLoader());
            config.setCodec(codec);
            } catch (Exception e) {
                throw new IllegalStateException("Unable to initialize codec with ClassLoader parameter", e);
            }
            
            return Redisson.create(config);
        } catch (Exception e) {
            throw new LifecycleException(e);
        }
    }

    @Override
    protected void stopInternal() throws LifecycleException {
        super.stopInternal();
        
        setState(LifecycleState.STOPPING);
        
        try {
            if (redisson != null) {
                redisson.shutdown();
            }
        } catch (Exception e) {
            throw new LifecycleException(e);
        }
        
    }

    public void store(HttpSession session) throws IOException {
        if (session == null) {
            return;
        }
        
        if (updateMode == UpdateMode.AFTER_REQUEST) {
            RedissonSession sess = (RedissonSession) findSession(session.getId());
            sess.save();            
        }
    }
    
}
