/*
 * Copyright 2007 Sun Microsystems, Inc.
 *
 * This file is part of Project Darkstar Server.
 *
 * Project Darkstar Server is free software: you can redistribute it
 * and/or modify it under the terms of the GNU General Public License
 * version 2 as published by the Free Software Foundation and
 * distributed hereunder to you.
 *
 * Project Darkstar Server is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.sun.sgs.impl.service.channel;

import com.sun.sgs.app.Channel;
import com.sun.sgs.app.ChannelListener;
import com.sun.sgs.app.ChannelManager;
import com.sun.sgs.app.ClientSession;
import com.sun.sgs.app.ClientSessionId;
import com.sun.sgs.app.Delivery;
import com.sun.sgs.app.ManagedObject;
import com.sun.sgs.app.ManagedReference;
import com.sun.sgs.app.NameExistsException;
import com.sun.sgs.app.NameNotBoundException;
import com.sun.sgs.app.ObjectNotFoundException;
import com.sun.sgs.app.TransactionNotActiveException;
import com.sun.sgs.impl.service.session.ClientSessionImpl;
import com.sun.sgs.impl.sharedutil.CompactId;
import com.sun.sgs.impl.sharedutil.HexDumper;
import com.sun.sgs.impl.sharedutil.LoggerWrapper;
import com.sun.sgs.impl.sharedutil.MessageBuffer;
import com.sun.sgs.impl.sharedutil.PropertiesWrapper;
import com.sun.sgs.impl.util.AbstractKernelRunnable;
import com.sun.sgs.impl.util.AbstractService;
import com.sun.sgs.impl.util.BoundNamesUtil;
import com.sun.sgs.impl.util.Exporter;
import com.sun.sgs.impl.util.NonDurableTaskQueue;
import com.sun.sgs.impl.util.NonDurableTaskScheduler;
import com.sun.sgs.impl.util.TransactionContext;
import com.sun.sgs.impl.util.TransactionContextFactory;
import com.sun.sgs.impl.util.TransactionContextMap;
import com.sun.sgs.kernel.ComponentRegistry;
import com.sun.sgs.kernel.TaskOwner;
import com.sun.sgs.kernel.TaskScheduler;
import com.sun.sgs.protocol.simple.SimpleSgsProtocol;
import com.sun.sgs.service.ClientSessionService;
import com.sun.sgs.service.DataService;
import com.sun.sgs.service.Node;
import com.sun.sgs.service.ProtocolMessageListener;
import com.sun.sgs.service.RecoveryCompleteFuture;
import com.sun.sgs.service.RecoveryListener;
import com.sun.sgs.service.Service;
import com.sun.sgs.service.TaskService;
import com.sun.sgs.service.Transaction;
import com.sun.sgs.service.TransactionProxy;
import com.sun.sgs.service.WatchdogService;
import java.io.Serializable;
import java.math.BigInteger;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Simple ChannelService implementation. <p>
 * 
 * The {@link #ChannelServiceImpl constructor} requires the <a
 * href="../../../app/doc-files/config-properties.html#com.sun.sgs.app.name">
 * <code>com.sun.sgs.app.name</code></a> property. <p>
 */
public class ChannelServiceImpl
    extends AbstractService implements ChannelManager
{
    /** The name of this class. */
    private static final String CLASSNAME = ChannelServiceImpl.class.getName();

    private static final String PKG_NAME = "com.sun.sgs.impl.service.channel";

    /** The prefix of a session key which maps to its channel membership. */
    private static final String SESSION_PREFIX = PKG_NAME + ".session.";
    
    /** The prefix of a channel key which maps to its channel state. */
    private static final String CHANNEL_STATE_PREFIX = PKG_NAME + ".state.";
    
    /** The logger for this class. */
    private static final LoggerWrapper logger =
	new LoggerWrapper(Logger.getLogger(PKG_NAME));

    /** The name of the server port property. */
    private static final String SERVER_PORT_PROPERTY =
	PKG_NAME + ".server.port";
	
    /** The default server port. */
    private static final int DEFAULT_SERVER_PORT = 0;
    
    /**
     * The transaction context map, or null if configure has not been called.
     */
    private static volatile TransactionContextMap<Context> contextMap = null;

    /** List of contexts that have been prepared (non-readonly) or commited. */
    private final List<Context> contextList = new LinkedList<Context>();

    /** The task scheduler. */
    private final TaskScheduler taskScheduler;

    private volatile TaskOwner taskOwner;

    /** The data service. */
    private final DataService dataService;

    /** The watchdog service. */
    private final WatchdogService watchdogService;

    /** The client session service. */
    private final ClientSessionService sessionService;

    /** The task scheduler for non-durable tasks. */
    volatile NonDurableTaskScheduler nonDurableTaskScheduler;

    /** The transaction context factory. */
    private final TransactionContextFactory<Context> contextFactory;
    
    /** The exporter for the ChannelServer. */
    private final Exporter<ChannelServer> exporter;

    /** The ChannelServer remote interface implementation. */
    private final ChannelServerImpl serverImpl;
	
    /** The proxy for the ChannelServer. */
    private final ChannelServer serverProxy;

    /** The ID for the local node. */
    private final long localNodeId;

    /** Map (with weak keys) of client sessions to queues, each containing
     * tasks to forward channel messages sent by the session (the key).
     */
    private final WeakHashMap<ClientSession, NonDurableTaskQueue>
	taskQueues = new WeakHashMap<ClientSession, NonDurableTaskQueue>();
    
    /** The sequence number for channel messages originating from the server. */
    private final AtomicLong sequenceNumber = new AtomicLong(0);

    /** A map of channel ID to cached channel state, valid as of the
     * last transaction commit. */
    private Map<CompactId, CachedChannelState> channelStateCache =
	Collections.synchronizedMap(
	    new HashMap<CompactId, CachedChannelState>());

    /**
     * Constructs an instance of this class with the specified {@code
     * properties}, {@code systemRegistry}, and {@code txnProxy}.
     *
     * @param	properties service properties
     * @param	systemRegistry system registry
     * @param	txnProxy transaction proxy
     *
     * @throws Exception if a problem occurs when creating the service
     */
    public ChannelServiceImpl(Properties properties,
			      ComponentRegistry systemRegistry,
			      TransactionProxy txnProxy)
	throws Exception
    {
	super(properties, systemRegistry, txnProxy, logger);
	
	logger.log(
	    Level.CONFIG, "Creating ChannelServiceImpl properties:{0}",
	    properties);
	PropertiesWrapper wrappedProps = new PropertiesWrapper(properties);

	try {
	    synchronized (ChannelServiceImpl.class) {
		if (ChannelServiceImpl.contextMap == null) {
		    contextMap = new TransactionContextMap<Context>(txnProxy);
		}
	    }
	    contextFactory = new ContextFactory(contextMap);
	    taskScheduler = systemRegistry.getComponent(TaskScheduler.class);
	    dataService = txnProxy.getService(DataService.class);
	    watchdogService = txnProxy.getService(WatchdogService.class);
	    sessionService = txnProxy.getService(ClientSessionService.class);
	    localNodeId = watchdogService.getLocalNodeId();
	    taskOwner = txnProxy.getCurrentOwner();
	    
	    /*
	     * Export the ChannelServer.
	     */
	    int serverPort = wrappedProps.getIntProperty(
		SERVER_PORT_PROPERTY, DEFAULT_SERVER_PORT, 0, 65535);
	    serverImpl = new ChannelServerImpl();
	    exporter = new Exporter<ChannelServer>(ChannelServer.class);
	    try {
		int port = exporter.export(serverImpl, serverPort);
		serverProxy = exporter.getProxy();
		logger.log(
		    Level.CONFIG, "export successful. port:{0,number,#}", port);
	    } catch (Exception e) {
		try {
		    exporter.unexport();
		} catch (RuntimeException re) {
		}
		throw e;
	    }

	    /*
	     * Store the ChannelServer proxy in the data store.
	     */
	    taskScheduler.runTransactionalTask(
		new AbstractKernelRunnable() {
		    public void run() {
			dataService.setServiceBinding(
			    getChannelServerKey(localNodeId),
			    new ChannelServerWrapper(serverProxy));
		    }},
		txnProxy.getCurrentOwner());

	    /*
	     * Add listeners for handling recovery and for handling
	     * protocol messages for the channel service.
	     */
	    watchdogService.addRecoveryListener(
		new ChannelServiceRecoveryListener());
	    sessionService.registerProtocolMessageListener(
		SimpleSgsProtocol.CHANNEL_SERVICE,
		new ChannelProtocolMessageListener());

	} catch (Exception e) {
	    if (logger.isLoggable(Level.CONFIG)) {
		logger.logThrow(
		    Level.CONFIG, e, "Failed to create ChannelServiceImpl");
	    }
	    throw e;
	}
    }
 
    /* -- Implement AbstractService methods -- */

    /** {@inheritDoc} */
    protected void doReady() {
	taskOwner = txnProxy.getCurrentOwner();
        nonDurableTaskScheduler =
		new NonDurableTaskScheduler(
		    taskScheduler, txnProxy.getCurrentOwner(),
		    txnProxy.getService(TaskService.class));
    }

    /** {@inheritDoc} */
    protected void doShutdown() {
	logger.log(Level.FINEST, "shutdown");
	
	try {
	    exporter.unexport();
	} catch (RuntimeException e) {
	    logger.logThrow(Level.FINEST, e, "unexport server throws");
	    // swallow exception
	}
    }
    
    /* -- Implement ChannelManager -- */

    /** {@inheritDoc} */
    public Channel createChannel(String name,
				 ChannelListener listener,
				 Delivery delivery)
    {
	try {
	    if (name == null) {
		throw new NullPointerException("null name");
	    }
	    if (listener != null && !(listener instanceof Serializable)) {
		throw new IllegalArgumentException(
		    "listener is not serializable");
	    }
	    Context context = contextFactory.joinTransaction();
	    Channel channel = context.createChannel(name, listener, delivery);
	    if (logger.isLoggable(Level.FINEST)) {
		logger.log(
		    Level.FINEST, "createChannel name:{0} returns {1}",
		    name, channel);
	    }
	    return channel;
	    
	} catch (RuntimeException e) {
	    if (logger.isLoggable(Level.FINEST)) {
		logger.logThrow(
		    Level.FINEST, e, "createChannel name:{0} throws", name);
	    }
	    throw e;
	}
    }

    /** {@inheritDoc} */
    public Channel getChannel(String name) {
	try {
	    if (name == null) {
		throw new NullPointerException("null name");
	    }
	    Context context = contextFactory.joinTransaction();
	    Channel channel = context.getChannel(name);
	    if (logger.isLoggable(Level.FINEST)) {
		logger.log(
		    Level.FINEST, "getChannel name:{0} returns {1}",
		    name, channel);
	    }
	    return channel;
	    
	} catch (RuntimeException e) {
	    if (logger.isLoggable(Level.FINEST)) {
		logger.logThrow(
		    Level.FINEST, e, "getChannel name:{0} throws", name);
	    }
	    throw e;
	}
    }

    /* -- Implement TransactionContextFactory -- */
       
    private class ContextFactory extends TransactionContextFactory<Context> {
	ContextFactory(TransactionContextMap<Context> contextMap) {
	    super(contextMap);
	}
	
	public Context createContext(Transaction txn) {
	    return new Context(txn);
	}
    }

    /**
     * Iterates through the context list, in order, to flush any
     * committed changes.  During iteration, this method invokes
     * {@code flush} on the {@code Context} returned by {@code next}.
     * Iteration ceases when either a context's {@code flush} method
     * returns {@code false} (indicating that the transaction
     * associated with the context has not yet committed) or when
     * there are no more contexts in the context list.
     */
    private void flushContexts() {
	synchronized (contextList) {
	    Iterator<Context> iter = contextList.iterator();
	    while (iter.hasNext()) {
		Context context = iter.next();
		if (context.flush()) {
		    iter.remove();
		} else {
		    break;
		}
	    }
	}
    }

    /**
     * Checks that the specified context is currently active, throwing
     * TransactionNotActiveException if it isn't.
     */
    static void checkContext(Context context) {
	getContextMap().checkContext(context);
    }

    /**
     * Returns the transaction context map.
     *
     * @return the transaction context map
     */
    private synchronized static TransactionContextMap<Context> getContextMap()
    {
	if (contextMap == null) {
	    throw new IllegalStateException("Service not configured");
	}
	return contextMap;
    }

    /* -- Implement ChannelServer -- */

    private final class ChannelServerImpl implements ChannelServer {
	
	/** {@inheritDoc} */
	public void join(byte[] channelId, long nodeId) {
	    callStarted();
	    try {
		throw new AssertionError("not implemented");
	    } finally {
		callFinished();
	    }
	}
	
	/** {@inheritDoc} */
	public void leave(byte[] channelId, long nodeId) {
	    callStarted();
	    try {
		throw new AssertionError("not implemented");
	    } finally {
		callFinished();
	    }
	}

	/** {@inheritDoc} */
	public void leaveAll(byte[] channelId, byte[][] sessionIds) {
	    callStarted();
	    try {
		throw new AssertionError("not implemented");
	    } finally {
		callFinished();
	    }
	}
	
	/** {@inheritDoc} */
	public void send(byte[] channelId, byte[][] recipients, byte[] message) {
	    callStarted();
	    try {
		throw new AssertionError("not implemented");
	    } finally {
		callFinished();
	    }
	}
	
	/** {@inheritDoc} */
	public void close(byte[] channelId, long nodeId) {
	    callStarted();
	    try {
		throw new AssertionError("not implemented");
	    } finally {
		callFinished();
	    }
	}
    }
    
    /* -- Implement ProtocolMessageListener -- */

    private final class ChannelProtocolMessageListener
	implements ProtocolMessageListener
    {
	/** {@inheritDoc} */
	public void receivedMessage(ClientSession session, byte[] message) {
	    try {
		MessageBuffer buf = new MessageBuffer(message);
	    
		buf.getByte(); // discard version
		
		/*
		 * Handle service id.
		 */
		byte serviceId = buf.getByte();

		if (serviceId != SimpleSgsProtocol.CHANNEL_SERVICE) {
		    if (logger.isLoggable(Level.SEVERE)) {
			logger.log(
                            Level.SEVERE,
			    "expected channel service ID, got: {0}",
			    serviceId);
		    }
		    return;
		}

		/*
		 * Handle op code.
		 */
		byte opcode = buf.getByte();

		switch (opcode) {
		    
		case SimpleSgsProtocol.CHANNEL_SEND_REQUEST:

		    handleChannelSendRequest(session, buf);
		    break;
		    
		default:
		    if (logger.isLoggable(Level.SEVERE)) {
			logger.log(
			    Level.SEVERE,
			    "receivedMessage session:{0} message:{1} " +
			    "unknown opcode:{2}",
			    session, HexDumper.format(message), opcode);
		    }
		    break;
		}

		if (logger.isLoggable(Level.FINEST)) {
		    logger.log(
			Level.FINEST,
			"receivedMessage session:{0} message:{1} returns",
			session, HexDumper.format(message));
		}
		
	    } catch (RuntimeException e) {
		if (logger.isLoggable(Level.SEVERE)) {
		    logger.logThrow(
			Level.SEVERE, e,
			"receivedMessage session:{0} message:{1} throws",
			session, HexDumper.format(message));
		}
	    }
	}

	/** {@inheritDoc} */
	public void disconnected(final ClientSession session) {
	    /*
	     * Remove session from all channels that it is currently a
	     * member of.
	     */
	    nonDurableTaskScheduler.scheduleTask(
		new AbstractKernelRunnable() {
		    public void run() {
			removeSessionFromAllChannels(session);
		    }},
		session.getIdentity());
	}
    }

    /**
     * Handles a CHANNEL_SEND_REQUEST protocol message (in the given
     * {@code buf} and sent by the given {@code sender}), forwarding
     * the channel message (encapsulated in {@code buf}) to the
     * appropriate recipients.  When this method is invoked, the
     * specified message buffer's current position points to the
     * channel ID in the protocol message.  The operation code has
     * already been processed by the caller.
     */
    private void handleChannelSendRequest(
	ClientSession sender, MessageBuffer buf)
    {
	CompactId channelId = CompactId.getCompactId(buf);
	CachedChannelState cachedState = channelStateCache.get(channelId);
	if (cachedState == null) {
	    // TBD: is this the right logging level?
	    logger.log(
		Level.WARNING,
		"non-existent channel:{0}, dropping message", channelId);
	    return;
	}

	// Ensure that sender is a channel member before continuing.
	if (!cachedState.hasSession(sender)) {
	    if (logger.isLoggable(Level.WARNING)) {
		logger.log(
		    Level.WARNING,
		    "send attempt on channel:{0} by non-member session:{1}, " +
		    "dropping message", channelId, sender);
	    }
	    return;
	}
	
	long seq = buf.getLong(); // TODO Check sequence num
	short numRecipients = buf.getShort();
	if (numRecipients < 0) {
	    if (logger.isLoggable(Level.WARNING)) {
		logger.log(
		    Level.WARNING,
		    "bad CHANNEL_SEND_REQUEST " +
		    "(negative number of recipients) " +
		    "numRecipents:{0} session:{1}",
		    numRecipients, sender);
	    }
	    return;
	}

	Set<ClientSession> recipients = new HashSet<ClientSession>();
	if (numRecipients == 0) {
	    // Recipients are all member sessions
	    recipients = cachedState.sessions;
	} else {
	    // Look up recipient sessions and check for channel membership
	    for (int i = 0; i < numRecipients; i++) {
		CompactId recipientId = CompactId.getCompactId(buf);
		ClientSession recipient =
		    sessionService.getClientSession(recipientId.getId());
		if (recipient != null && cachedState.hasSession(recipient)) {
		    recipients.add(recipient);
		}
	    }
	}

	byte[] channelMessage = buf.getByteArray();
	byte[] protocolMessage =
	    getChannelMessage(
		channelId, ((ClientSessionImpl) sender).getCompactSessionId(),
		channelMessage, seq);
	
	if (logger.isLoggable(Level.FINEST)) {
	    logger.log(
		Level.FINEST,
		"name:{0}, message:{1}",
		cachedState.name, HexDumper.format(channelMessage));
	}

	ClientSessionId senderId = sender.getSessionId();
	for (ClientSession session : recipients) {
	    // Send channel protocol message, skipping the sender
	    if (! senderId.equals(session.getSessionId())) {
		sessionService.sendProtocolMessageNonTransactional(
		    session, protocolMessage, cachedState.delivery);
	    }
        }

	if (cachedState.hasChannelListeners) {
	    NonDurableTaskQueue queue = getTaskQueue(sender);
	    // Notify listeners in the app in a transaction
	    queue.addTask(
		new NotifyTask(cachedState.name, channelId,
			       senderId, channelMessage));
	}
    }
    
    /**
     * Returns the task queue for the specified {@code session}.
     * If a queue does not already exist, one is created and returned.
     */
    private NonDurableTaskQueue getTaskQueue(ClientSession session) {
	synchronized (taskQueues) {
	    NonDurableTaskQueue queue = taskQueues.get(session);
	    if (queue == null) {
		queue =
		    new NonDurableTaskQueue(
			txnProxy, nonDurableTaskScheduler,
			session.getIdentity());
		taskQueues.put(session, queue);
	    }
	    return queue;
	}
    }
    
    /**
     * Stores information relating to a specific transaction operating on
     * channels.
     *
     * <p>This context maintains an internal table that maps (for the
     * channels used in the context's associated transaction) channel name
     * to channel implementation.  To create, obtain, or remove a channel
     * within a transaction, the {@code createChannel},
     * {@code getChannel}, or {@code removeChannel} methods
     * (respectively) must be called on the context so that the proper
     * channel instances are used.
     */
    final class Context extends TransactionContext {

	/**
	 * Map of channel name to transient channel impl (for those
	 * channels used during this context's associated
	 * transaction).
	 */
	private final Map<String, ChannelImpl> internalTable =
	    new HashMap<String, ChannelImpl>();

	/**
	 * Constructs a context with the specified transaction. 
	 */
	private Context(Transaction txn) {
	    super(txn);
	}

	/* -- ChannelManager methods -- */

	/**
	 * Creates a channel with the specified {@code name}, {@code
	 * listener}, and {@code delivery} requirement.  The channel's
	 * state is bound to a name composed of the channel service's
	 * prefix followed by ".state." followed by the channel name.
	 */
	private Channel createChannel(String name,
				      ChannelListener listener,
				      Delivery delivery)
	{
	    assert name != null;
	    String key = getChannelStateKey(name);
	    try {
		dataService.getServiceBinding(key, ChannelState.class);
		throw new NameExistsException(name);
	    } catch (NameNotBoundException e) {
	    }
	    
	    ChannelState channelState =
		new ChannelState(name, listener, delivery, dataService);
	    dataService.setServiceBinding(key, channelState);
	    ChannelImpl channel = new ChannelImpl(this, channelState);
	    internalTable.put(name, channel);
	    return channel;
	}

	/**
	 * Returns a channel with the specified {@code name}.  If the
	 * channel is already present in the internal channel table
	 * for this transaction, then the channel is returned;
	 * otherwise, this method gets the channel's state by looking
	 * up the service binding for the channel.
	 */
	private Channel getChannel(String name) {
	    assert name != null;
	    ChannelImpl channel = internalTable.get(name);
	    if (channel == null) {
		ChannelState channelState;
		try {
		    channelState =
		    	dataService.getServiceBinding(
			    getChannelStateKey(name), ChannelState.class);
		} catch (NameNotBoundException e) {
		    throw new NameNotBoundException(name);
		}
		channel =  new ChannelImpl(this, channelState);
		internalTable.put(name, channel);
	    } else if (channel.isClosed) {
		throw new NameNotBoundException(name);
	    }
	    return channel;
	}

	/**
	 * Returns a channel with the specified {@code name} and
	 * {@code channelId}.  If the channel is already present in the
	 * internal channel table for this transaction, then the
	 * channel is returned; otherwise, this method uses the {@code
	 * channelId} as a {@code ManagedReference} ID to the
	 * channel's state.
	 */
	private Channel getChannel(String name, CompactId channelId) {
	    assert channelId != null;
	    ChannelImpl channel = internalTable.get(name);
	    if (channel == null) {
		ChannelState channelState;
		try {
		    BigInteger refId = new BigInteger(1, channelId.getId());
		    ManagedReference stateRef =
			dataService.createReferenceForId(refId);
		    channelState = stateRef.get(ChannelState.class);
		} catch (ObjectNotFoundException e) {
		    throw new NameNotBoundException(name);
		}
		channel = new ChannelImpl(this, channelState);
		internalTable.put(name, channel);
	    } else if (channel.isClosed) {
		throw new NameNotBoundException(name);
	    }
	    return channel;
	}

	/* -- transaction participant methods -- */

	/**
	 * Throws a {@code TransactionNotActiveException} if this
	 * transaction is prepared.
	 */
	private void checkPrepared() {
	    if (isPrepared) {
		throw new TransactionNotActiveException("Already prepared");
	    }
	}
	
	/**
	 * Marks this transaction as prepared, and if there are
	 * pending changes, adds this context to the context list and
	 * returns {@code false}.  Otherwise, if there are no pending
	 * changes returns {@code true} indicating readonly status.
	 */
        public boolean prepare() {
	    isPrepared = true;
	    boolean readOnly = internalTable.isEmpty();
	    if (! readOnly) {
		synchronized (contextList) {
		    contextList.add(this);
		}
	    }
            return readOnly;
        }

	/**
	 * Marks this transaction as aborted, removes the context from
	 * the context list containing pending updates, and flushes
	 * all committed contexts preceding prepared ones.
	 */
	public void abort(boolean retryable) {
	    synchronized (contextList) {
		contextList.remove(this);
	    }
	    flushContexts();
	}

	/**
	 * Marks this transaction as committed and flushes all
	 * committed contexts preceding prepared ones.
	 */
	public void commit() {
	    isCommitted = true;
	    flushContexts();
        }

	/**
	 * If the context is committed, flushes channel state updates
	 * to the channel state cache and returns true; otherwise
	 * returns false.
	 */
	private boolean flush() {
	    if (isCommitted) {
		for (ChannelImpl channel : internalTable.values()) {
		    if (channel.isClosed) {
			channelStateCache.remove(channel.state.id);
		    } else {
			channelStateCache.put(
			    channel.state.id,
			    new CachedChannelState(channel));
		    }
		}
		return true;
	    } else {
		return false;
	    }
	}

	/* -- other methods -- */

	/**
	 * Returns the client session service.
	 */
	ClientSessionService getClientSessionService() {
	    return sessionService;
	}

	/**
	 * Returns the local node ID.
	 */
	long getLocalNodeId() {
	    return localNodeId;
	}

	/**
	 * Returns a service of the given {@code type}.
	 */
	<T extends Service> T getService(Class<T> type) {
	    return txnProxy.getService(type);
	}

	/**
	 * Returns the next sequence number for messages originating
	 * from this service.
	 */
	long nextSequenceNumber() {
	    return sequenceNumber.getAndIncrement();
	}
    }

    /**
     * Task (transactional) for notifying channel listeners.
     */
    private final class NotifyTask extends AbstractKernelRunnable {

	private final String name;
	private final CompactId channelId;
	private final ClientSessionId senderId;
	private final byte[] message;

        NotifyTask(String name,
		   CompactId channelId,
		   ClientSessionId senderId,
		   byte[] message)
	{
	    this.name = name;
	    this.channelId = channelId;
	    this.senderId = senderId;
	    this.message = message;
	}

        /** {@inheritDoc} */
	public void run() {
	    try {
                if (logger.isLoggable(Level.FINEST)) {
                    logger.log(
                        Level.FINEST,
                        "NotifyTask.run name:{0}, message:{1}",
                        name, HexDumper.format(message));
                }
		Context context = contextFactory.joinTransaction();
		ChannelImpl channel =
		    (ChannelImpl) context.getChannel(name, channelId);
		channel.notifyListeners(senderId, message);

	    } catch (RuntimeException e) {
		if (logger.isLoggable(Level.FINER)) {
		    logger.logThrow(
			Level.FINER, e,
			"NotifyTask.run name:{0}, message:{1} throws",
			name, HexDumper.format(message));
		}
		throw e;
	    }
	}
    }
    
    /**
     * Contains cached channel state, stored in the {@code channelStateCache}
     * map when a committed context is flushed.
     */
    private class CachedChannelState {

	private final String name;
	private final Set<ClientSession> sessions;
	private final boolean hasChannelListeners;
	private final Delivery delivery;

	CachedChannelState(ChannelImpl channelImpl) {
	    this.name = channelImpl.state.name;
	    this.sessions =
		channelImpl.state.getSessions(dataService, localNodeId);
	    this.hasChannelListeners = channelImpl.state.hasChannelListeners();
	    this.delivery = channelImpl.state.delivery;
	}

	boolean hasSession(ClientSession session) {
	    // FIXME: this only works for sessions on the local node
	    return sessions.contains(session);
	}
    }

    /**
     * The {@code RecoveryListener} for handling requests to recover
     * for a failed {@code ChannelService}.
     */
    private class ChannelServiceRecoveryListener
	implements RecoveryListener
    {
	/** {@inheritDoc} */
	public void recover(final Node node, RecoveryCompleteFuture future) {
	    try {
		taskScheduler.runTransactionalTask(
		    new RecoveryTask(node.getId()), taskOwner);
		future.done();
	    } catch (Exception e) {
		logger.logThrow(
 		    Level.WARNING, e,
		    "removing sessions from channels for node:{0} throws",
		    node.getId());
		// TBD: what should it do if it can't recover?
	    }
	}
    }

    /**
     * Task to perform recovery actions for a specific node,
     * specifically to remove all client sessions that were connected
     * to the node from all channels that those sessions were a member
     * of.
     */
    private class RecoveryTask extends AbstractKernelRunnable {

	private final long nodeId;

	RecoveryTask(long nodeId) {
	    this.nodeId = nodeId;
	}

	/** {@inheritDoc} */
	public void run() {
	    Iterator<ClientSession> iter =
		ChannelState.getSessionsAnyChannel(dataService, nodeId);
	    while (iter.hasNext()) {
		removeSessionFromAllChannels(iter.next());
	    }
	}
    }

    /**
     * Removes the specified client {@code session} from all channels
     * that it is currently a member of.
     */
    private void removeSessionFromAllChannels(ClientSession session) {
	Set<String> channelNames =
	    ChannelState.getChannelsForSession(dataService, session);
	for (String name : channelNames) {
	    try {
		getChannel(name).leave(session);
	    } catch (NameNotBoundException e) {
		logger.logThrow(Level.FINE, e, "channel removed:{0}", name);
	    }
	}
    }

    /**
     * Returns the key for accessing the {@code ChannelState} instance
     * for the channel with the specified {@code channelName}.
     */
    private static String getChannelStateKey(String channelName) {
	return PKG_NAME + ".state." + channelName;
    }

    /**
     * Returns the key for accessing the {@code ChannelServer}
     * instance (which is wrapped in a {@code ChannelServerWrapper})
     * for the specified {@code nodeId}.
     */
    static String getChannelServerKey(long nodeId) {
	return PKG_NAME + ".server." + nodeId;
    }

    /**
     * Returns a MessageBuffer containing a CHANNEL_MESSAGE protocol
     * message with this channel's name, and the specified sender,
     * message, and sequence number.
     */
    static byte[] getChannelMessage(
	CompactId channelId, CompactId senderId,
	byte[] message, long sequenceNumber)
    {
        MessageBuffer buf =
            new MessageBuffer(13 + channelId.getExternalFormByteCount() +
			      senderId.getExternalFormByteCount() +
			      message.length);
        buf.putByte(SimpleSgsProtocol.VERSION).
            putByte(SimpleSgsProtocol.CHANNEL_SERVICE).
            putByte(SimpleSgsProtocol.CHANNEL_MESSAGE).
            putBytes(channelId.getExternalForm()).
            putLong(sequenceNumber).
            putBytes(senderId.getExternalForm()).
	    putByteArray(message);

        return buf.getBuffer();
    }
}
