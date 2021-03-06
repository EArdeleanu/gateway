/**
 * Copyright (c) 2007-2014 Kaazing Corporation. All rights reserved.
 * 
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.kaazing.gateway.transport.http;

import static java.lang.String.format;
import static java.util.Collections.unmodifiableMap;
import static java.util.EnumSet.allOf;
import static java.util.EnumSet.complementOf;
import static java.util.EnumSet.of;
import static org.kaazing.gateway.resource.address.ResourceAddress.NEXT_PROTOCOL;
import static org.kaazing.gateway.resource.address.ResourceAddress.QUALIFIER;
import static org.kaazing.gateway.transport.BridgeSession.LOCAL_ADDRESS;
import static org.kaazing.gateway.transport.http.HttpConnectFilter.CONTENT_LENGTH_ADJUSTMENT;
import static org.kaazing.gateway.transport.http.HttpConnectFilter.PROTOCOL_HTTPXE;
import static org.kaazing.gateway.transport.http.HttpHeaders.HEADER_CONTENT_LENGTH;
import static org.kaazing.gateway.transport.http.bridge.filter.HttpNextProtocolHeaderFilter.PROTOCOL_HTTPXE_1_1;
import static org.kaazing.gateway.transport.http.bridge.filter.HttpProtocolFilter.PROTOCOL_HTTP_1_1;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;

import javax.annotation.Resource;

import org.apache.mina.core.filterchain.IoFilterChain;
import org.apache.mina.core.future.ConnectFuture;
import org.apache.mina.core.future.DefaultConnectFuture;
import org.apache.mina.core.future.IoFuture;
import org.apache.mina.core.future.IoFutureListener;
import org.apache.mina.core.service.IoHandler;
import org.apache.mina.core.service.TransportMetadata;
import org.apache.mina.core.session.IdleStatus;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.core.session.IoSessionInitializer;
import org.apache.mina.util.ConcurrentHashSet;
import org.kaazing.gateway.resource.address.ResourceAddress;
import org.kaazing.gateway.resource.address.ResourceAddressFactory;
import org.kaazing.gateway.transport.AbstractBridgeConnector;
import org.kaazing.gateway.transport.BridgeConnector;
import org.kaazing.gateway.transport.BridgeServiceFactory;
import org.kaazing.gateway.transport.DefaultIoSessionConfigEx;
import org.kaazing.gateway.transport.DefaultTransportMetadata;
import org.kaazing.gateway.transport.ExceptionLoggingFilter;
import org.kaazing.gateway.transport.IoHandlerAdapter;
import org.kaazing.gateway.transport.NamedPipeAddress;
import org.kaazing.gateway.transport.ObjectLoggingFilter;
import org.kaazing.gateway.transport.TypedAttributeKey;
import org.kaazing.gateway.transport.http.bridge.HttpContentMessage;
import org.kaazing.gateway.transport.http.bridge.HttpMessage;
import org.kaazing.gateway.transport.http.bridge.HttpResponseMessage;
import org.kaazing.gateway.transport.http.bridge.filter.HttpBuffer;
import org.kaazing.gateway.transport.http.bridge.filter.HttpBufferAllocator;
import org.kaazing.gateway.transport.http.bridge.filter.HttpFilterAdapter;
import org.kaazing.gateway.util.InternalSystemProperty;
import org.kaazing.mina.core.buffer.IoBufferAllocatorEx;
import org.kaazing.mina.core.buffer.IoBufferEx;
import org.kaazing.mina.core.service.IoProcessorEx;
import org.kaazing.mina.core.session.IoSessionEx;
import org.kaazing.mina.netty.socket.nio.NioSocketChannelIoSession;
import org.kaazing.mina.netty.util.threadlocal.VicariousThreadLocal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HttpConnector extends AbstractBridgeConnector<DefaultHttpSession> {

    private static final TypedAttributeKey<Callable<DefaultHttpSession>> HTTP_SESSION_FACTORY_KEY = new TypedAttributeKey<>(HttpConnector.class, "httpSessionFactory");
    private static final TypedAttributeKey<DefaultHttpSession> HTTP_SESSION_KEY = new TypedAttributeKey<>(HttpConnector.class, "httpSession");
    private static final TypedAttributeKey<ConnectFuture> HTTP_CONNECT_FUTURE_KEY = new TypedAttributeKey<>(HttpConnector.class, "httpConnectFuture");

    
    private static final String FAULT_LOGGING_FILTER = HttpProtocol.NAME + "#fault";
    private static final String TRACE_LOGGING_FILTER = HttpProtocol.NAME + "#logging";
    private static final String TRUNCATE_CONTENT_FILTER = HttpProtocol.NAME + "#truncate-content";

    private static final String LOGGER_NAME = String.format("transport.%s.connect", HttpProtocol.NAME);

    private final Logger logger = LoggerFactory.getLogger(LOGGER_NAME);

    private final Map<String, Set<HttpConnectFilter>> connectFiltersByProtocol;
    private final Set<HttpConnectFilter> allConnectFilters;
    private BridgeServiceFactory bridgeServiceFactory;
    private ResourceAddressFactory addressFactory;
    private final ThreadLocal<PersistentConnectionPool> persistentConnectionsStore;
    private final ConcurrentHashSet<Executor> ioExecutors;
    private Properties configuration;

    public HttpConnector() {
        super(new DefaultIoSessionConfigEx());
        
        // note: content length adjustment filter is added dynamically for httpxe/1.1, and not needed by http/1.1
        Map<String, Set<HttpConnectFilter>> connectFiltersByProtocol = new HashMap<>();
        connectFiltersByProtocol.put(PROTOCOL_HTTP_1_1, complementOf(of(CONTENT_LENGTH_ADJUSTMENT, PROTOCOL_HTTPXE)));
        connectFiltersByProtocol.put(PROTOCOL_HTTPXE_1_1, complementOf(of(CONTENT_LENGTH_ADJUSTMENT)));
        this.connectFiltersByProtocol = unmodifiableMap(connectFiltersByProtocol);
        this.allConnectFilters = allOf(HttpConnectFilter.class);
        this.persistentConnectionsStore = new VicariousThreadLocal<PersistentConnectionPool>() {
            @Override
            protected PersistentConnectionPool initialValue() {
                return new PersistentConnectionPool(logger);
            }
        };
        this.ioExecutors = new ConcurrentHashSet<>();
    }
    
    @Resource(name = "bridgeServiceFactory")
    public void setBridgeServiceFactory(BridgeServiceFactory bridgeServiceFactory) {
        this.bridgeServiceFactory = bridgeServiceFactory;
    }

    @Resource(name = "resourceAddressFactory")
    public void setResourceAddressFactory(ResourceAddressFactory resourceAddressFactory) {
        this.addressFactory = resourceAddressFactory;
    }

    @Resource(name = "configuration")
    public void setConfiguration(Properties configuration) {
        this.configuration = configuration;
    }

    @Override
    protected IoProcessorEx<DefaultHttpSession> initProcessor() {
        return new HttpConnectProcessor(persistentConnectionsStore);
    }

    @Override
    public TransportMetadata getTransportMetadata() {
        return new DefaultTransportMetadata(HttpProtocol.NAME);
    }

    @Override
    protected boolean canConnect(String transportName) {
        return transportName.equals("http");
    }

    @Override
    protected <T extends ConnectFuture> ConnectFuture connectInternal(final ResourceAddress address,
                final IoHandler handler, final IoSessionInitializer<T> initializer) {

        final ConnectFuture connectFuture =  new DefaultConnectFuture();
        final ResourceAddress transportAddress = address.getTransport();

        if (transportAddress != null) {
            Executor ioExecutor = null;
            boolean ioAligned = isIoAligned();
            if (!ioAligned) {
                ioExecutor = getIoExecutor();
            }

            if (ioAligned || ioExecutor == null) {
                connectInternal0(connectFuture, address, handler, initializer);
            } else {
                ioExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        connectInternal0(connectFuture, address, handler, initializer);
                    }
                });
            }
        }

        return connectFuture;
    }

    // Returns a random Executor from the available ones
    // @return a random executor
    //         otherwise null
    private Executor getIoExecutor() {
        Integer workerCount = null;
        if (configuration != null) {
            workerCount = InternalSystemProperty.TCP_PROCESSOR_COUNT.getIntProperty(configuration);
        }

        if (workerCount != null && !ioExecutors.isEmpty()) {
            int index = ThreadLocalRandom.current().nextInt(workerCount);
            int i = 0;
            for (Executor ioExecutor : ioExecutors) {
                if (i == index) {
                    return ioExecutor;
                }
                i = i + 1;
            }
        }
        return null;
    }

    private <T extends ConnectFuture> void connectInternal0(ConnectFuture connectFuture,
            final ResourceAddress address, final IoHandler handler, final IoSessionInitializer<T> initializer) {

        IoSession transportSession = persistentConnectionsStore.get().take(address.getTransport());
        if (transportSession != null) {
            connectUsingExistingTransport(connectFuture, address, transportSession, handler, initializer);
        } else {
            connectUsingNewTransport(connectFuture, address, handler, initializer);
        }

    }

    protected <T extends ConnectFuture> void connectUsingExistingTransport(final ConnectFuture connectFuture,
                ResourceAddress address, IoSession transportSession, IoHandler handler,
                IoSessionInitializer<T> initializer) {

        IoSessionInitializer<T> httpSessionInitializer = createHttpSessionInitializer(handler, initializer);

        Callable<DefaultHttpSession> httpSessionFactory = createHttpSession(address, transportSession, httpSessionInitializer, connectFuture);

        HTTP_SESSION_FACTORY_KEY.set(transportSession, httpSessionFactory);
        HTTP_CONNECT_FUTURE_KEY.set(transportSession, connectFuture);

        try {
            bridgeHandler.sessionOpened(transportSession);
        } catch (Exception e) {
            connectFuture.setException(e);
        }
    }

    private <T extends ConnectFuture> void connectUsingNewTransport(final ConnectFuture connectFuture,
               ResourceAddress address, IoHandler handler, IoSessionInitializer<T> initializer) {

        // propagate connection failure, if necessary
        IoFutureListener<ConnectFuture> parentConnectListener = new IoFutureListener<ConnectFuture>() {
            @Override
            public void operationComplete(ConnectFuture future) {
                // fail bridge connect future if parent connect fails
                if (!future.isConnected()) {
                    connectFuture.setException(future.getException());
                } else {
                    IoSessionEx session = (IoSessionEx)future.getSession();
                    ioExecutors.add(session.getIoExecutor());
                }
            }
        };

        ResourceAddress transportAddress = address.getTransport();
        BridgeConnector connector = bridgeServiceFactory.newBridgeConnector(transportAddress);
        IoSessionInitializer<ConnectFuture> parentInitializer = createParentInitializer(address,
                handler, initializer, connectFuture);
        connector.connect(transportAddress, bridgeHandler, parentInitializer).addListener(parentConnectListener);

    }

    @Override
    public void addBridgeFilters(IoFilterChain chain) {
        // setup logging filters for bridge session
        if (logger.isTraceEnabled()) {
            chain.addFirst(TRACE_LOGGING_FILTER, new ObjectLoggingFilter(logger, HttpProtocol.NAME + "#%s"));
        } else if (logger.isDebugEnabled()) {
            chain.addFirst(FAULT_LOGGING_FILTER, new ExceptionLoggingFilter(logger, HttpProtocol.NAME + "#%s"));
        }

        IoSession transport = chain.getSession();

        SocketAddress localAddress = transport.getLocalAddress();
        String nextProtocol = PROTOCOL_HTTP_1_1;

        if (localAddress instanceof ResourceAddress) {
            ResourceAddress address = (ResourceAddress) localAddress;
            if (!address.hasOption(QUALIFIER)) {
                nextProtocol = address.getOption(NEXT_PROTOCOL);
            }
        }

        assert nextProtocol != null;
        Set<HttpConnectFilter> connectFilters = connectFiltersByProtocol.get(nextProtocol);
        assert (connectFilters != null && !connectFilters.isEmpty());

        for (HttpConnectFilter connectFilter : connectFilters) {
            chain.addLast(connectFilter.filterName(), connectFilter.filter());
        }
    }

    @Override
    public void removeBridgeFilters(IoFilterChain filterChain) {
        for (HttpConnectFilter filter : allConnectFilters) {
            switch (filter) {
            case CODEC:
                // Note: we MUST NOT remove the codec filter until
                //       after the first IoBuffer is received post-upgrade
                break;
            default:
                removeFilter(filterChain, filter.filterName());
                break;
            }
        }

        if (filterChain.contains(TRACE_LOGGING_FILTER)) {
            filterChain.remove(TRACE_LOGGING_FILTER);
        } else if (filterChain.contains(FAULT_LOGGING_FILTER)) {
            filterChain.remove(FAULT_LOGGING_FILTER);
        }
    }

    @Override
    protected void finishSessionInitialization0(IoSession session, IoFuture future) {
        DefaultHttpSession httpSession = (DefaultHttpSession)session;
        HttpConnectProcessor processor = (HttpConnectProcessor)httpSession.getProcessor();
        processor.finishConnect(httpSession);
    }

    private <T extends ConnectFuture> IoSessionInitializer<ConnectFuture> createParentInitializer(final ResourceAddress connectAddress,
            final IoHandler handler, final IoSessionInitializer<T> initializer, final ConnectFuture httpConnectFuture) {
        // initialize parent session before connection attempt
        return new IoSessionInitializer<ConnectFuture>() {
            @Override
            public void initializeSession(final IoSession parent, ConnectFuture future) {
                // initializer for bridge session to specify bridge handler,
                // and call user-defined bridge session initializer if present
                final IoSessionInitializer<T> httpSessionInitializer = createHttpSessionInitializer(handler, initializer);

                // factory to create a new bridge session
                Callable<DefaultHttpSession> createSession = createHttpSession(connectAddress, parent, httpSessionInitializer, httpConnectFuture);

                HTTP_SESSION_FACTORY_KEY.set(parent, createSession);
                HTTP_CONNECT_FUTURE_KEY.set(parent, httpConnectFuture);
            }

        };
    }


    // initializer for bridge session to specify bridge handler,
    // and call user-defined bridge session initializer if present
    private <T extends ConnectFuture> IoSessionInitializer<T> createHttpSessionInitializer(final IoHandler handler, final IoSessionInitializer<T> initializer) {
        return new IoSessionInitializer<T>() {
            @Override
            public void initializeSession(IoSession session, T future) {
                DefaultHttpSession httpSession = (DefaultHttpSession) session;
                httpSession.setHandler(handler);

                if (initializer != null) {
                    initializer.initializeSession(session, future);
                }
            }
        };
    }

    // factory to create a new bridge session
    private <T extends ConnectFuture> Callable<DefaultHttpSession> createHttpSession(final ResourceAddress connectAddress,
                final IoSession parent, final IoSessionInitializer<T> httpSessionInitializer,
                final ConnectFuture connectFuture) {
        return new Callable<DefaultHttpSession>() {
            @Override
            public DefaultHttpSession call() throws Exception {

                //TODO: Using instanceof is brittle.
                // support connecting http over pipes / socket addresses
                setLocalAddressFromSocketAddress(parent,
                        parent instanceof NioSocketChannelIoSession ? "tcp" : "udp");

                ResourceAddress transportAddress = LOCAL_ADDRESS.get(parent);
                final ResourceAddress localAddress =
                        addressFactory.newResourceAddress(connectAddress, transportAddress);

                Callable<DefaultHttpSession> httpSessionFactory = new Callable<DefaultHttpSession>() {
                    @Override
                    public DefaultHttpSession call() throws Exception {
                        IoSessionEx parentEx = (IoSessionEx) parent;
                        IoBufferAllocatorEx<?> parentAllocator = parentEx.getBufferAllocator();
                        DefaultHttpSession httpSession = new DefaultHttpSession(HttpConnector.this,
                                getProcessor(),
                                localAddress,
                                connectAddress,
                                parentEx,
                                new HttpBufferAllocator(parentAllocator));
                        parent.setAttribute(HTTP_SESSION_KEY, httpSession);
                        return httpSession;
                    }
                };

                return newSession(httpSessionInitializer, connectFuture, httpSessionFactory);
            }

            private void setLocalAddressFromSocketAddress(final IoSession session,
                                                          final String transportName) {
                SocketAddress socketAddress = session.getLocalAddress();
                if (socketAddress instanceof InetSocketAddress) {
                    InetSocketAddress inetSocketAddress = (InetSocketAddress) socketAddress;
                    ResourceAddress resourceAddress = newResourceAddress(inetSocketAddress,
                            transportName);
                    LOCAL_ADDRESS.set(session, resourceAddress);
                }
                else if (socketAddress instanceof NamedPipeAddress) {
                    NamedPipeAddress namedPipeAddress = (NamedPipeAddress) socketAddress;
                    ResourceAddress resourceAddress = newResourceAddress(namedPipeAddress,
                            "pipe");
                    LOCAL_ADDRESS.set(session, resourceAddress);
                }
            }


            public  ResourceAddress newResourceAddress(NamedPipeAddress namedPipeAddress,
                                                       final String transportName) {
                String addressFormat = "%s://%s";
                String pipeName = namedPipeAddress.getPipeName();
                URI transport = URI.create(format(addressFormat, transportName, pipeName));
                return addressFactory.newResourceAddress(transport);
            }

            public  ResourceAddress newResourceAddress(InetSocketAddress inetSocketAddress,
                                                       final String transportName) {
                InetAddress inetAddress = inetSocketAddress.getAddress();
                String hostAddress = inetAddress.getHostAddress();
                String addressFormat = (inetAddress instanceof Inet6Address) ? "%s://[%s]:%s" : "%s://%s:%s";
                int port = inetSocketAddress.getPort();
                URI transport = URI.create(format(addressFormat, transportName, hostAddress, port));
                return addressFactory.newResourceAddress(transport);
            }

        };
    }

    private IoHandler bridgeHandler = new IoHandlerAdapter<IoSessionEx>() {


        @Override
        @SuppressWarnings("unchecked")
        protected void doSessionOpened(IoSessionEx session) throws Exception {

            IoFilterChain filterChain = session.getFilterChain();
            addBridgeFilters(filterChain);

            Callable<DefaultHttpSession> sessionFactory = HTTP_SESSION_FACTORY_KEY.remove(session);
            DefaultHttpSession httpSession = sessionFactory.call();

            HTTP_SESSION_KEY.set(session, httpSession);
            HTTP_CONNECT_FUTURE_KEY.remove(session);
        }

        @Override
        protected void doSessionClosed(IoSessionEx session) throws Exception {
            DefaultHttpSession httpSession = HTTP_SESSION_KEY.get(session);
            if (httpSession != null && !httpSession.isClosing()) {
                httpSession.reset(new Exception("Early termination of IO session").fillInStackTrace());
                return;
            }

            if (!session.isClosing()) {
                IoFilterChain filterChain = session.getFilterChain();
                removeBridgeFilters(filterChain);
            }
        }

        @Override
        protected void doExceptionCaught(IoSessionEx session, Throwable cause)
                throws Exception {
            DefaultHttpSession httpSession = HTTP_SESSION_KEY.get(session);
            if (httpSession != null && !httpSession.isClosing()) {
                httpSession.reset(cause);
            }
            else {
                ConnectFuture httpConnectFuture = HTTP_CONNECT_FUTURE_KEY.remove(session);
                if (httpConnectFuture != null) {
                    httpConnectFuture.setException(cause);
                }
                else {
                    if (logger.isDebugEnabled()) {
                        String message = format("Error on HTTP connection attempt: %s", cause);
                        if (logger.isTraceEnabled()) {
                            // note: still debug level, but with extra detail about the exception
                            logger.debug(message, cause);
                        }
                        else {
                            logger.debug(message);
                        }
                    }
                }
                session.close(true);
            }
        }

        @Override
        protected void doSessionIdle(IoSessionEx session, IdleStatus status)
                throws Exception {
            // TODO Auto-generated method stub
            super.doSessionIdle(session, status);
        }

        @Override
        protected void doMessageReceived(final IoSessionEx session, Object message) throws Exception {
            // TODO: if content is complete then suspendRead on iosession
            // TODO: in processor when complete resume iosession read (parent)

            DefaultHttpSession httpSession = HTTP_SESSION_KEY.get(session);
            HttpMessage httpMessage = (HttpMessage) message;
            switch (httpMessage.getKind()) {
            case RESPONSE:
                HttpResponseMessage httpResponse = (HttpResponseMessage)httpMessage;
                HttpStatus httpStatus = httpResponse.getStatus();
                httpSession.setStatus(httpStatus);
                httpSession.setReason(httpResponse.getReason());
                httpSession.setVersion(httpResponse.getVersion());
                httpSession.setReadHeaders(httpResponse.getHeaders());

                String contentLength = httpSession.getReadHeader(HEADER_CONTENT_LENGTH);
                if (contentLength != null) {
                    final long maximumReadBytes = Long.parseLong(contentLength);
                    session.getFilterChain().addLast(TRUNCATE_CONTENT_FILTER, new HttpFilterAdapter<IoSessionEx>() {

                        @Override
                        protected void httpContentReceived(NextFilter nextFilter, IoSessionEx session, HttpContentMessage httpContent) throws Exception {
                            // trigger session closed when all content arrived
                            // even if underlying IoSession is kept open
                            if (session.getReadBytes() >= maximumReadBytes) {
                                session.close(false);
                            }

                            super.httpContentReceived(nextFilter, session, httpContent);
                        }

                    });
                }

                switch (httpStatus) {
                case INFO_SWITCHING_PROTOCOLS:
                    // handle upgrade
                    httpSession.close(false);
                    break;
                case SUCCESS_OK:
                    switch (httpSession.getMethod()) {
                    case HEAD:
                        httpSession.close(false);
                        break;
                    default:
                        HttpContentMessage httpContent = httpResponse.getContent();
                        if (httpContent == null) {
                            IoBufferAllocatorEx<? extends HttpBuffer> allocator = httpSession.getBufferAllocator();
                            httpContent = new HttpContentMessage(allocator.wrap(allocator.allocate(0)), true);
                        }
                        fireContentReceived(httpSession, httpContent);
                    }
                    break;
                case SUCCESS_NO_CONTENT:
                case REDIRECT_MOVED_PERMANENTLY:
                case REDIRECT_NOT_MODIFIED:
                    httpSession.close(false);
                    break;
                default:
                    HttpContentMessage httpContent = httpResponse.getContent();
                    if (httpContent == null) {
                        IoBufferAllocatorEx<? extends HttpBuffer> allocator = httpSession.getBufferAllocator();
                        httpContent = new HttpContentMessage(allocator.wrap(allocator.allocate(0)), true);
                    }
                    fireContentReceived(httpSession, httpContent);
                    break;
                }
                break;
            case CONTENT:
                HttpContentMessage httpContent = (HttpContentMessage)httpMessage;
                fireContentReceived(httpSession, httpContent);
                break;
            default:
                throw new IllegalArgumentException("Unexpected HTTP message: " + httpMessage.getKind());
            }
        }

        private void fireContentReceived(HttpSession session, HttpContentMessage content) {
            IoBufferEx buffer = content.asBuffer();
            if (buffer != null && buffer.hasRemaining()) {
                IoFilterChain filterChain = session.getFilterChain();
                filterChain.fireMessageReceived(buffer);
            }

            // deliver the session close event when we receive the last chunk
            if (content.isComplete()) {
                session.close(false);
            }
        }
    };
}
