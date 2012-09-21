package com.cloudera.flume.handlers.json;

/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
import com.cloudera.flume.handlers.avro.AvroNettyTransceiver;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.apache.avro.ipc.Transceiver;
import org.jboss.netty.bootstrap.ClientBootstrap;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelEvent;
import org.jboss.netty.channel.ChannelFactory;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.ChannelState;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;
import org.jboss.netty.handler.codec.http.DefaultHttpChunk;
import org.jboss.netty.handler.codec.http.HttpClientCodec;
import org.jboss.netty.handler.stream.ChunkedWriteHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A Netty-based {@link Transceiver} implementation.
 *
 * Based off the one in Avro IPC but modified to check channel.isWritable()
 * before writing. That way, slow/blocked downstream servers don't cause buffers
 * to fill up, ultimately causing OutOfMemoryErrors.
 */
public class JsonNettyTransceiver {

    /**
     * If not specified, the default connection timeout will be used (60 sec).
     */
    public static final long DEFAULT_CONNECTION_TIMEOUT_MILLIS = 60 * 1000L;
    public static final String NETTY_CONNECT_TIMEOUT_OPTION = "connectTimeoutMillis";
    public static final String NETTY_TCP_NODELAY_OPTION = "tcpNoDelay";
    public static final boolean DEFAULT_TCP_NODELAY_VALUE = true;
    private static final Logger LOG = LoggerFactory
            .getLogger(JsonNettyTransceiver.class.getName());
    private final ChannelFactory channelFactory;
    private final long connectTimeoutMillis;
    private final ClientBootstrap bootstrap;
    private final InetSocketAddress remoteAddr;
    private final AtomicLong sentEvents;
    private final AtomicLong droppedEvents;
    private final Timer eventsLogTimer;
    volatile ChannelFuture channelFuture;
    volatile boolean stopping;
    private final Object channelFutureLock = new Object();
    /**
     * Read lock must be acquired whenever using non-final state. Write lock
     * must be acquired whenever modifying state.
     */
    private final ReentrantReadWriteLock stateLock = new ReentrantReadWriteLock();
    private Channel channel; // Synchronized on stateLock

    JsonNettyTransceiver() {
        channelFactory = null;
        connectTimeoutMillis = 0L;
        bootstrap = null;
        remoteAddr = null;

        sentEvents = null;
        droppedEvents = null;
        eventsLogTimer = null;
        channelFuture = null;
    }

    /**
     * Creates a NettyTransceiver, and attempts to connect to the given address.
     * {@link #DEFAULT_CONNECTION_TIMEOUT_MILLIS} is used for the connection
     * timeout.
     *
     * @param addr the address to connect to.
     * @throws IOException if an error occurs connecting to the given address.
     */
    public JsonNettyTransceiver(InetSocketAddress addr) throws IOException {
        this(addr, DEFAULT_CONNECTION_TIMEOUT_MILLIS);
    }

    /**
     * Creates a NettyTransceiver, and attempts to connect to the given address.
     *
     * @param addr the address to connect to.
     * @param connectTimeoutMillis maximum amount of time to wait for connection
     * establishment in milliseconds, or null to use
     * {@link #DEFAULT_CONNECTION_TIMEOUT_MILLIS}.
     * @throws IOException if an error occurs connecting to the given address.
     */
    public JsonNettyTransceiver(InetSocketAddress addr,
            Long connectTimeoutMillis) throws IOException {
        this(
                addr,
                new NioClientSocketChannelFactory(getBossExecutorService(), getWorkerExecutorService()),
                connectTimeoutMillis);
    }

    /**
     * Creates a NettyTransceiver, and attempts to connect to the given address.
     * {@link #DEFAULT_CONNECTION_TIMEOUT_MILLIS} is used for the connection
     * timeout.
     *
     * @param addr the address to connect to.
     * @param channelFactory the factory to use to create a new Netty Channel.
     * @throws IOException if an error occurs connecting to the given address.
     */
    public JsonNettyTransceiver(InetSocketAddress addr,
            ChannelFactory channelFactory) throws IOException {
        this(addr, channelFactory, buildDefaultBootstrapOptions(null));
    }

    /**
     * Creates a NettyTransceiver, and attempts to connect to the given address.
     *
     * @param addr the address to connect to.
     * @param channelFactory the factory to use to create a new Netty Channel.
     * @param connectTimeoutMillis maximum amount of time to wait for connection
     * establishment in milliseconds, or null to use
     * {@link #DEFAULT_CONNECTION_TIMEOUT_MILLIS}.
     * @throws IOException if an error occurs connecting to the given address.
     */
    public JsonNettyTransceiver(InetSocketAddress addr,
            ChannelFactory channelFactory, Long connectTimeoutMillis)
            throws IOException {
        this(addr, channelFactory,
                buildDefaultBootstrapOptions(connectTimeoutMillis));
    }

    /**
     * Creates a NettyTransceiver, and attempts to connect to the given address.
     * It is strongly recommended that the {@link #NETTY_CONNECT_TIMEOUT_OPTION}
     * option be set to a reasonable timeout value (a Long value in
     * milliseconds) to prevent connect/disconnect attempts from hanging
     * indefinitely. It is also recommended that the
     * {@link #NETTY_TCP_NODELAY_OPTION} option be set to true to minimize RPC
     * latency.
     *
     * @param addr the address to connect to.
     * @param channelFactory the factory to use to create a new Netty Channel.
     * @param nettyClientBootstrapOptions map of Netty ClientBootstrap options
     * to use.
     * @throws IOException if an error occurs connecting to the given address.
     */
    public JsonNettyTransceiver(InetSocketAddress addr,
            ChannelFactory channelFactory,
            Map<String, Object> nettyClientBootstrapOptions) throws IOException {
        if (channelFactory == null) {
            throw new NullPointerException("channelFactory is null");
        }

        // Set up.
        this.channelFactory = channelFactory;
        this.connectTimeoutMillis = (Long) nettyClientBootstrapOptions
                .get(NETTY_CONNECT_TIMEOUT_OPTION);
        bootstrap = new ClientBootstrap(channelFactory);
        remoteAddr = addr;

        // Configure the event pipeline factory.
        bootstrap.setPipelineFactory(new ChannelPipelineFactory() {
            @Override
            public ChannelPipeline getPipeline() throws Exception {
                ChannelPipeline p = Channels.pipeline();
                p.addLast("httpHandler", new HttpClientCodec());
                p.addLast("chunkedWriter", new ChunkedWriteHandler());
                p.addLast("handler", new NettyClientHandler());
                return p;
            }
        });

        if (nettyClientBootstrapOptions != null) {
            LOG.debug("Using Netty bootstrap options: "
                    + nettyClientBootstrapOptions);
            bootstrap.setOptions(nettyClientBootstrapOptions);
        }

        //for (String key: bootstrap.getOptions().keySet()) {
        //    LOG.info("ClientBootstrap option: " + key + "=" + bootstrap.getOption(key));
        //}

        // Make a new connection.
        stateLock.readLock().lock();
        try {
            getChannel();
        }
        finally {
            stateLock.readLock().unlock();
        }

        sentEvents = new AtomicLong();
        droppedEvents = new AtomicLong();
        eventsLogTimer = new Timer();

        TimerTask eventsLogTimerTask = new TimerTask() {
            @Override
            public void run() {
                if (droppedEvents.longValue() > 0l) {
                    LOG.info("[host: " + remoteAddr.getHostName() + ", port: " + remoteAddr.getPort() + "] Sent " + sentEvents.longValue() + " events in the past minute.");
                    LOG.info("[host: " + remoteAddr.getHostName() + ", port: " + remoteAddr.getPort() + "] Dropped " + droppedEvents.longValue() + " events in the past minute due to full TCP write buffer.");
                    droppedEvents.set(0l);
                }
                if (sentEvents.longValue() > 0l) {
                    sentEvents.set(0l);
                }
            }
        };

        // schedule the task to run starting now and then every hour...
        Calendar start = Calendar.getInstance();
        start.add(Calendar.MINUTE, 1);
        start.set(Calendar.SECOND, 0);
        start.set(Calendar.MILLISECOND, 0);
        eventsLogTimer.scheduleAtFixedRate(eventsLogTimerTask, start.getTime(), 1000 * 60);
    }

    private static ExecutorService getBossExecutorService() {
        return new ThreadPoolExecutor(1, 10, 30, TimeUnit.SECONDS,
                new LinkedBlockingQueue<Runnable>(10000),
                new AvroNettyTransceiver.NettyTransceiverThreadFactory("Json " + JsonNettyTransceiver.class.getSimpleName() + " Boss"),
                new ThreadPoolExecutor.DiscardPolicy());
    }

    private static ExecutorService getWorkerExecutorService() {
        return new ThreadPoolExecutor(1, 10, 30, TimeUnit.SECONDS,
                new LinkedBlockingQueue<Runnable>(10000),
                new AvroNettyTransceiver.NettyTransceiverThreadFactory("Json " + JsonNettyTransceiver.class.getSimpleName() + " I/O Worker"),
                new ThreadPoolExecutor.DiscardPolicy());
    }

    /**
     * Creates the default options map for the Netty ClientBootstrap.
     *
     * @param connectTimeoutMillis connection timeout in milliseconds, or null
     * if no timeout is desired.
     * @return the map of Netty bootstrap options.
     */
    private static Map<String, Object> buildDefaultBootstrapOptions(
            Long connectTimeoutMillis) {
        Map<String, Object> options = new HashMap<String, Object>(2);
        options.put(NETTY_TCP_NODELAY_OPTION, DEFAULT_TCP_NODELAY_VALUE);
        options.put(
                NETTY_CONNECT_TIMEOUT_OPTION,
                connectTimeoutMillis == null ? DEFAULT_CONNECTION_TIMEOUT_MILLIS
                : connectTimeoutMillis);

        options.put("writeBufferLowWaterMark", 64 * 1024);
        options.put("writeBufferHighWaterMark", 10 * 64 * 1024);
        options.put("sendBufferSize", 1048576);
        options.put("receiveBufferSize", 1048576);

        return options;
    }

    /**
     * Tests whether the given channel is ready for writing.
     *
     * @return true if the channel is open and ready; false otherwise.
     */
    private static boolean isChannelReady(Channel channel) {
        return (channel != null) && channel.isOpen() && channel.isBound()
                && channel.isConnected();
    }

    /**
     * Gets the Netty channel. If the channel is not connected, first attempts
     * to connect. NOTE: The stateLock read lock *must* be acquired before
     * calling this method.
     *
     * @return the Netty channel
     * @throws IOException if an error occurs connecting the channel.
     */
    private Channel getChannel() throws IOException {
        if (!isChannelReady(channel)) {
            // Need to reconnect
            // Upgrade to write lock
            stateLock.readLock().unlock();
            stateLock.writeLock().lock();
            try {
                if (!isChannelReady(channel)) {
                    synchronized (channelFutureLock) {
                        if (!stopping) {
                            LOG.debug("Connecting to " + remoteAddr);
                            channelFuture = bootstrap.connect(remoteAddr);
                        }
                    }
                    if (channelFuture != null) {
                        channelFuture.awaitUninterruptibly(connectTimeoutMillis);

                        synchronized (channelFutureLock) {
                            if (!channelFuture.isSuccess()) {
                                throw new IOException("Error connecting to " + remoteAddr,
                                        channelFuture.getCause());
                            }
                            channel = channelFuture.getChannel();
                            channelFuture = null;
                        }
                    }
                }
            } finally {
                // Downgrade to read lock:
                stateLock.readLock().lock();
                stateLock.writeLock().unlock();
            }
        }
        return channel;
    }

    /**
     * Closes the connection to the remote peer if connected.
     */
    private void disconnect() {
        disconnect(false, false, null);
    }

    /**
     * Closes the connection to the remote peer if connected.
     *
     * @param awaitCompletion if true, will block until the close has completed.
     * @param cancelPendingRequests if true, will drain the requests map and
     * send an IOException to all Callbacks.
     * @param cause if non-null and cancelPendingRequests is true, this
     * Throwable will be passed to all Callbacks.
     */
    private void disconnect(boolean awaitCompletion, boolean cancelPendingRequests,
            Throwable cause) {
        Channel channelToClose = null;
        boolean stateReadLockHeld = stateLock.getReadHoldCount() != 0;

        synchronized (channelFutureLock) {
            if (stopping && channelFuture != null) {
                channelFuture.cancel();
            }
        }
        if (stateReadLockHeld) {
            stateLock.readLock().unlock();
        }
        stateLock.writeLock().lock();
        try {
            if (channel != null) {
                if (cause != null) {
                    LOG.debug("Disconnecting from " + remoteAddr, cause);
                } else {
                    LOG.debug("Disconnecting from " + remoteAddr);
                }
                channelToClose = channel;
                channel = null;
            }
        } finally {
            if (stateReadLockHeld) {
                stateLock.readLock().lock();
            }
            stateLock.writeLock().unlock();
        }

        // Close the channel:
        if (channelToClose != null) {
            ChannelFuture closeFuture = channelToClose.close();
            if (awaitCompletion && (closeFuture != null)) {
                closeFuture.awaitUninterruptibly(connectTimeoutMillis);
            }
        }
    }

    public void close() {
        // Close the connection:
        stopping = true;
        disconnect(true, true, null);
    }

    public void writeData(byte[] data) throws IOException {
        stateLock.readLock().lock();
        try {
            if (getChannel().isWritable()) {
                getChannel().write(new DefaultHttpChunk(ChannelBuffers.wrappedBuffer(ByteBuffer.wrap(data))));
                sentEvents.incrementAndGet();
            } else {
                droppedEvents.incrementAndGet();
            }
        } finally {
            stateLock.readLock().unlock();
        }
    }

    public boolean isConnected() {
        stateLock.readLock().lock();
        try {
            return channel != null && channel.isConnected();
        } finally {
            stateLock.readLock().unlock();
        }
    }

    /**
     * Client handler for the Netty transport
     */
    class NettyClientHandler extends SimpleChannelUpstreamHandler {

        @Override
        public void handleUpstream(ChannelHandlerContext ctx, ChannelEvent e)
                throws Exception {
            if (e instanceof ChannelStateEvent) {
                LOG.debug(e.toString());
                ChannelStateEvent cse = (ChannelStateEvent) e;
                if ((cse.getState() == ChannelState.OPEN)
                        && (Boolean.FALSE.equals(cse.getValue()))) {
                    // Server closed connection; disconnect client side
                    LOG.debug("Remote peer " + remoteAddr
                            + " closed connection.");
                    disconnect(false, true, null);
                }
            }
            super.handleUpstream(ctx, e);
        }

        @Override
        public void channelOpen(ChannelHandlerContext ctx, ChannelStateEvent e)
                throws Exception {
            // channel = e.getChannel();
            super.channelOpen(ctx, e);
        }

        @Override
        public void channelClosed(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
            super.channelClosed(ctx, e);
            channelFactory.releaseExternalResources();
            eventsLogTimer.cancel();
            eventsLogTimer.purge();
        }


        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e) {
            disconnect(false, true, e.getCause());
        }
    }

    /**
     * Creates threads with unique names based on a specified name prefix.
     */
    public static class NettyTransceiverThreadFactory implements ThreadFactory {

        private final AtomicInteger threadId = new AtomicInteger(0);
        private final String prefix;

        /**
         * Creates a NettyTransceiverThreadFactory that creates threads with the
         * specified name.
         *
         * @param prefix the name prefix to use for all threads created by this
         * ThreadFactory. A unique ID will be appended to this prefix to form
         * the final thread name.
         */
        public NettyTransceiverThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread thread = new Thread(r);
            thread.setName(prefix + " " + threadId.incrementAndGet());
            return thread;
        }
    }
}
