package com.thomsonreuters.upa.valueadd.reactor;

import java.nio.channels.SelectableChannel;
import java.util.HashMap;

import com.thomsonreuters.upa.codec.Codec;
import com.thomsonreuters.upa.codec.CodecFactory;
import com.thomsonreuters.upa.codec.DataStates;
import com.thomsonreuters.upa.codec.DataTypes;
import com.thomsonreuters.upa.codec.Msg;
import com.thomsonreuters.upa.codec.MsgClasses;
import com.thomsonreuters.upa.codec.RefreshMsg;
import com.thomsonreuters.upa.codec.StateCodes;
import com.thomsonreuters.upa.codec.StatusMsg;
import com.thomsonreuters.upa.codec.StreamStates;
import com.thomsonreuters.upa.transport.Channel;
import com.thomsonreuters.upa.transport.IoctlCodes;
import com.thomsonreuters.upa.transport.Server;
import com.thomsonreuters.upa.transport.TransportBuffer;
import com.thomsonreuters.upa.tunnelstream.TunnelStreamFactory;
import com.thomsonreuters.upa.tunnelstream.TunnelManager;
import com.thomsonreuters.upa.tunnelstream.TunnelStreamReadInfo;
import com.thomsonreuters.upa.valueadd.common.VaNode;
import com.thomsonreuters.upa.valueadd.domainrep.rdm.MsgBase;

/**
* Channel representing a connection handled by a Reactor.
* @see Reactor
* @see Reactor#connect
* @see Reactor#accept
* @see #close
*/
public class ReactorChannel extends VaNode
{
    private static final long NANO_PER_SEC = 1000000000;
    
    private Reactor _reactor = null;
    private ReactorRole _role = null;
    private SelectableChannel _selectableChannel = null;
    private SelectableChannel _oldSelectableChannel = null;
    private Channel _channel = null;
    private Server _server = null;
    private Object _userSpecObj = null;
    private StringBuilder _stringBuilder = new StringBuilder();
    private int _initializationTimeout = 0;
    private long _initializationEndTimeMs = 0;
    private PingHandler _pingHandler = new PingHandler();
    private ConnectionRecoveryHandler _connectionRecoveryHandler = new ConnectionRecoveryHandler();

    // tunnel stream support
    private TunnelManager _tunnelManager = TunnelStreamFactory.createTunnelManager();
    private HashMap<Integer,TunnelStream> _streamIdtoTunnelStreamTable = new HashMap<Integer,TunnelStream>();
    private TunnelStreamReadInfo _readInfo = TunnelStreamFactory.createTunnelStreamReadInfo();
    private Msg _tunnelStreamRespMsg = CodecFactory.createMsg();
    private ReactorSubmitOptions _reactorSubmitOptions = ReactorFactory.createReactorSubmitOptions();
    private ReactorChannelInfo _reactorChannelInfo = ReactorFactory.createReactorChannelInfo();
    private ClassOfService _defaultClassOfService = ReactorFactory.createClassOfService();
    private TunnelStreamRejectOptions _tunnelStreamRejectOptions = ReactorFactory.createTunnelStreamRejectOptions();
    
    // watchlist support
    private Watchlist _watchlist;

    /** The ReactorChannel's state. */
    public enum State
    {
        /** The ReactorChannel is in unknown state. This is the initial state before the channel is used. */
        UNKNOWN,
        /** The ReactorChannel is initializing its connection. */
        INITIALIZING,
        /** The ReactorChannel connection is up. */
        UP,
        /** The ReactorChannel connection is ready. It has received all messages configured for its role. */
        READY,
        /** The ReactorChannel connection is down and there is no connection recovery. */
        DOWN,
        /** The ReactorChannel connection is down and connection recovery will be started. */
        DOWN_RECONNECTING,
        /** The ReactorChannel connection is closed and the channel is no longer usable. */
        CLOSED
    };

    State _state = State.UNKNOWN;

    /**
     * The state of the ReactorChannel.
     * 
     * @return the state of the ReactorChannel
     */
    public State state()
    {
        return _state;
    }

    void state(State state)
    {
        _state = state;
        
        if (_state == State.DOWN_RECONNECTING && _watchlist != null)
        {
            _watchlist.channelDown();
        }
    }

    /**
     * Returns a String representation of this object.
     * 
     * @return string representation of this object
     */
    public String toString()
    {
        _stringBuilder.setLength(0);
        _stringBuilder.append("ReactorChannel: ");

        if (_role != null)
        {
            _stringBuilder.append(_role.toString() + " ");
        }
        else
        {
            _stringBuilder.append("no Role defined. ");
        }

        if (_channel == null && _selectableChannel != null)
        {
            _stringBuilder.append("Reactor's internal channel. ");
        }
        else
        {
            _stringBuilder.append("Associated with a transport.Channel. ");
        }

        if (_server != null)
        {
            _stringBuilder.append("Associated with a transport.Server. ");
        }

        if (_userSpecObj != null)
        {
            _stringBuilder.append("_userSpecObj=" + _userSpecObj.toString());
        }

        return _stringBuilder.toString();
    }

    void clear()
    {
        _state = State.UNKNOWN;
        _reactor = null;
        _role = null;
        _selectableChannel = null;
        _channel = null;
        _server = null;
        _userSpecObj = null;
        _initializationTimeout = 0;
        _initializationEndTimeMs = 0L;
        _pingHandler.clear();
        _connectionRecoveryHandler.clear();
        _streamIdtoTunnelStreamTable.clear();
        _tunnelStreamRespMsg.clear();
        if (_watchlist != null)
        {
            _watchlist.returnToPool();
            _watchlist = null;
        }
    }

    /** The {@link Reactor} associated with this ReactorChannel.
     *
     * @return the associated Reactor
     */
    public Reactor reactor()
    {
        return _reactor;
    }

    /** Sets the {@link Reactor} associated with this ReactorChannel.
    *
    * @param reactor the associated Reactor
    */
    public void reactor(Reactor reactor)
    {
        _reactor = reactor;
    }

    /**
     * The old SelectableChannel associated with this ReactorChannel.
     * Must be unregistered when handling a {@link ReactorChannelEventTypes#FD_CHANGE}
     * event.
     * 
     * @return old SelectableChannel
     */
    public SelectableChannel oldSelectableChannel()
    {
        return _oldSelectableChannel;
    }

    void oldSelectableChannel(SelectableChannel oldSelectableChannel)
    {
        _oldSelectableChannel = oldSelectableChannel;
    }
    
    /**
     * The SelectableChannel associated with this ReactorChannel.
     * Used to register with a selector.
     * 
     * @return SelectableChannel
     */
    public SelectableChannel selectableChannel()
    {
        return _selectableChannel;
    }

    void selectableChannel(SelectableChannel selectableChannel)
    {
        _channel = null;
        _selectableChannel = selectableChannel;
    }

    void selectableChannelFromChannel(Channel channel)
    {
        _channel = channel;
        if (channel != null)
        {
            _selectableChannel = channel.selectableChannel();
        }
    }

    /**
     * The {@link Channel} associated with this ReactorChannel.
     * 
     * @return Channel
     */
    public Channel channel()
    {
        return _channel;
    }

    /**
     * The {@link Server} associated with this ReactorChannel.
     * 
     * @return Server
     */
    public Server server()
    {
        return _server;
    }

    void server(Server server)
    {
        _server = server;
    }

    /* The role associated with this ReactorChannel. */
    ReactorRole role()
    {
        return _role;
    }

    void role(ReactorRole role)
    {
        _role = role;
    }

    /**
     * The user specified object associated with this ReactorChannel.
     * 
     * @return user specified object
     */
    public Object userSpecObj()
    {
        return _userSpecObj;
    }

    void userSpecObj(Object userSpecObj)
    {
        _userSpecObj = userSpecObj;
    }
    
    Watchlist watchlist()
    {
        return _watchlist;
    }
    
    void watchlist(Watchlist watchlist)
    {
        _watchlist = watchlist;
    }

    void initializationTimeout(int timeout)
    {
        _initializationTimeout = timeout;
        _initializationEndTimeMs = (timeout * 1000) + System.currentTimeMillis();
    }

    int initializationTimeout()
    {
        return _initializationTimeout;
    }

    long initializationEndTimeMs()
    {
        return _initializationEndTimeMs;
    }
    
    PingHandler pingHandler()
    {
    	return _pingHandler;
    }
    
    ConnectionRecoveryHandler connectionRecoveryHandler()
    {
    	return _connectionRecoveryHandler;
    }

    /**
     * Process this channel's events and messages from the Reactor. These are
     * passed to the calling application via the callback methods associated
     * with the channel.
     *
     * @param dispatchOptions options for how to dispatch
     * @param errorInfo error structure to be populated in the event of failure
     * 
     * @return a positive value if dispatching succeeded and there are more messages to process or
     * {@link ReactorReturnCodes#SUCCESS} if dispatching succeeded and there are no more messages to process or
     * {@link ReactorReturnCodes#FAILURE}, if dispatching failed (refer to errorInfo for additional information)
     */
    public int dispatch(ReactorDispatchOptions dispatchOptions, ReactorErrorInfo errorInfo)
    {
        if (errorInfo == null)
            return ReactorReturnCodes.FAILURE;
        else if (dispatchOptions == null)
            return reactor().populateErrorInfo(errorInfo, ReactorReturnCodes.FAILURE,
                                               "ReactorChannel.dispatch",
                                               "dispatchOptions cannot be null.");
        else if (_reactor.isShutdown())
            return _reactor.populateErrorInfo(errorInfo, ReactorReturnCodes.FAILURE,
                                              "ReactorChannel.dispatch",
                                              "Reactor is shutdown, dispatch aborted.");

        return _reactor.dispatchChannel(this, dispatchOptions, errorInfo);
    }

    /**
     * Sends the given TransportBuffer to the channel.
     * 
     * @param buffer the buffer to send
     * @param submitOptions options for how to send the message
     * @param errorInfo error structure to be populated in the event of failure
     * 
     * @return {@link ReactorReturnCodes#SUCCESS}, if submit succeeded or
     * {@link ReactorReturnCodes#WRITE_CALL_AGAIN}, if the buffer cannot be written at this time or
     * {@link ReactorReturnCodes#FAILURE}, if submit failed (refer to errorInfo for additional information)
     */
    public int submit(TransportBuffer buffer, ReactorSubmitOptions submitOptions, ReactorErrorInfo errorInfo)
    {
        if (errorInfo == null)
            return ReactorReturnCodes.FAILURE;
        else if (submitOptions == null)
            return reactor().populateErrorInfo(errorInfo, ReactorReturnCodes.FAILURE,
                                               "ReactorChannel.submit",
                                               "submitOptions cannot be null.");
        else if (_reactor.isShutdown())
            return _reactor.populateErrorInfo(errorInfo, ReactorReturnCodes.FAILURE,
                                              "ReactorChannel.submit",
                                              "Reactor is shutdown, submit aborted.");
        else if (_watchlist != null)
            return reactor().populateErrorInfo(errorInfo, ReactorReturnCodes.INVALID_USAGE,
                                               "ReactorChannel.submit",
                                               "Cannot submit buffer when watchlist is enabled.");

        return _reactor.submitChannel(this, buffer, submitOptions, errorInfo);
    }
    
    /**
     * Sends a message to the channel.
     * 
     * @param msg the message to send
     * @param submitOptions options for how to send the message
     * @param errorInfo error structure to be populated in the event of failure
     * 
     * @return {@link ReactorReturnCodes#SUCCESS}, if submit succeeded or
     * {@link ReactorReturnCodes#WRITE_CALL_AGAIN}, if the message cannot be written at this time or
     * {@link ReactorReturnCodes#NO_BUFFERS}, if there are no more buffers to encode the message into or
     * {@link ReactorReturnCodes#FAILURE}, if submit failed (refer to errorInfo for additional information)
     */
    public int submit(Msg msg, ReactorSubmitOptions submitOptions, ReactorErrorInfo errorInfo)
    {    
        if (errorInfo == null)
            return ReactorReturnCodes.FAILURE;
        else if (submitOptions == null)
            return reactor().populateErrorInfo(errorInfo, ReactorReturnCodes.FAILURE,
                                               "ReactorChannel.submit",
                                               "submitOptions cannot be null.");
        else if (_reactor.isShutdown())
            return _reactor.populateErrorInfo(errorInfo, ReactorReturnCodes.FAILURE,
                                              "ReactorChannel.submit",
                                              "Reactor is shutdown, submit aborted.");

        if (_watchlist == null) // watchlist not enabled, submit normally
        {
            return _reactor.submitChannel(this, msg, submitOptions, errorInfo);
        }
        else // watchlist enabled, submit via watchlist
        {
            return _watchlist.submitMsg(msg, submitOptions, errorInfo);
        }
    }

    /**
     * Sends an RDM message to the channel.
     * 
     * @param rdmMsg the RDM message to send
     * @param submitOptions options for how to send the message
     * @param errorInfo error structure to be populated in the event of failure
     * 
     * @return {@link ReactorReturnCodes#SUCCESS}, if submit succeeded or
     * {@link ReactorReturnCodes#WRITE_CALL_AGAIN}, if the message cannot be written at this time or
     * {@link ReactorReturnCodes#NO_BUFFERS}, if there are no more buffers to encode the message into or
     * {@link ReactorReturnCodes#FAILURE}, if submit failed (refer to errorInfo for additional information)
     */
    public int submit(MsgBase rdmMsg, ReactorSubmitOptions submitOptions, ReactorErrorInfo errorInfo)
    {    
        if (errorInfo == null)
            return ReactorReturnCodes.FAILURE;
        else if (submitOptions == null)
            return reactor().populateErrorInfo(errorInfo, ReactorReturnCodes.FAILURE,
                                               "ReactorChannel.submit",
                                               "submitOptions cannot be null.");
        else if (_reactor.isShutdown())
            return _reactor.populateErrorInfo(errorInfo, ReactorReturnCodes.FAILURE,
                                              "ReactorChannel.submit",
                                              "Reactor is shutdown, submit aborted.");

        if (_watchlist == null) // watchlist not enabled, submit normally
        {
            return _reactor.submitChannel(this, rdmMsg, submitOptions, errorInfo);
        }
        else // watchlist enabled, submit via watchlist
        {
            return _watchlist.submitMsg(rdmMsg, submitOptions, errorInfo);
        }
    }

    /**
     * Closes a reactor channel and removes it from the Reactor. May be called
     * inside or outside of a callback function, however the channel should no
     * longer be used afterwards.
     * 
     * @param errorInfo error structure to be populated in the event of failure
     * 
	 * @return {@link ReactorReturnCodes} indicating success or failure
     */
    public int close(ReactorErrorInfo errorInfo)
    {
    	int retVal = ReactorReturnCodes.SUCCESS;
    	
        if (errorInfo == null)
        	retVal = ReactorReturnCodes.FAILURE;
        if (_reactor.isShutdown())
        	retVal = _reactor.populateErrorInfo(errorInfo, ReactorReturnCodes.FAILURE,
                                              "ReactorChannel.close",
                                              "Reactor is shutdown, close aborted.");
        if (state() != State.CLOSED)
        	retVal = _reactor.closeChannel(this, errorInfo);
        
        returnToPool();
        
        return retVal;
    }

    /**
     * Gets a buffer from the ReactorChannel for writing a message.
     * 
     * @param size the size(in bytes) of the buffer to get
     * @param packedBuffer whether the buffer allows packing multiple messages
     *        via {@link #packBuffer(TransportBuffer, ReactorErrorInfo)}
     * @param errorInfo error structure to be populated in the event of failure
     * 
     * @return the buffer for writing the message or
     *         null, if an error occurred (errorInfo will be populated with information)
     */
	public TransportBuffer getBuffer(int size, boolean packedBuffer, ReactorErrorInfo errorInfo)
	{
		return channel().getBuffer(size, packedBuffer, errorInfo.error());
	}
	
	/**
	 * Returns an unwritten buffer to the ReactorChannel.
	 *
	 * @param buffer the buffer to release
     * @param errorInfo error structure to be populated in the event of failure
	 *
	 * @return {@link ReactorReturnCodes} indicating success or failure
	 */
	public int releaseBuffer(TransportBuffer buffer, ReactorErrorInfo errorInfo)
	{
		return channel().releaseBuffer(buffer, errorInfo.error());
	}
	
	/**
	 * Packs a buffer and returns the amount of available bytes remaining
     * in the buffer for packing.
	 * 
	 * @param buffer the buffer to be packed
	 * @param errorInfo error structure to be populated in the event of failure
	 * 
     * @return {@link ReactorReturnCodes} or the amount of available bytes remaining
     *         in the buffer for packing
	 */
	public int packBuffer(TransportBuffer buffer, ReactorErrorInfo errorInfo)
	{
		return channel().packBuffer(buffer, errorInfo.error());
	}
	
	/**
	 * Returns information about the ReactorChannel.
	 * 
	 * @param info ReactorChannelInfo structure to be populated with information
	 * @param errorInfo error structure to be populated in the event of failure
	 * 
	 * @return {@link ReactorReturnCodes} indicating success or failure
	 */
	public int info(ReactorChannelInfo info, ReactorErrorInfo errorInfo)
	{
		return channel().info(info.channelInfo(), errorInfo.error());
	}

	/**
	 * Retrieve the total number of used buffers for a ReactorChannel.
	 * 
	 * @param errorInfo error structure to be populated in the event of failure
	 * 
	 * @return if positive, the total number of buffers in use by this channel or
	 *         if negative, {@link ReactorReturnCodes} failure code
	 */
	public int bufferUsage(ReactorErrorInfo errorInfo)
	{
		return channel().bufferUsage(errorInfo.error());
	}
	
	/**
	 * Changes some aspects of the ReactorChannel.
	 * 
	 * @param code code indicating the option to change
	 * @param value value to change the option to
	 * @param errorInfo error structure to be populated in the event of failure
	 * 
	 * @return {@link ReactorReturnCodes} indicating success or failure
	 * 
	 * @see com.thomsonreuters.upa.transport.IoctlCodes
	 */
	public int ioctl(int code, int value, ReactorErrorInfo errorInfo)
	{
		return channel().ioctl(code, value, errorInfo.error());
	}

    /**
     * When a {@link ReactorChannel} becomes active for a client or server, this is
     * populated with the negotiated major version number that is associated
     * with the content being sent on this connection. Typically, a major
     * version increase is associated with the introduction of incompatible
     * change. The transport layer is data neutral and does not change nor
     * depend on any information in content being distributed. This information
     * is provided to help client and server applications manage the information
     * they are communicating.
     * 
     * @return the majorVersion
     */
    public int majorVersion()
    {
    	return (channel() != null ? channel().majorVersion() : Codec.majorVersion());
    }

    /**
     * When a {@link ReactorChannel} becomes active for a client or server, this is
     * populated with the negotiated minor version number that is associated
     * with the content being sent on this connection. Typically, a minor
     * version increase is associated with a fully backward compatible change or
     * extension. The transport layer is data neutral and does not change nor
     * depend on any information in content being distributed. This information
     * is provided to help client and server applications manage the information
     * they are communicating.
     * 
     * 
     * @return the minorVersion
     */
    public int minorVersion()
    {
    	return (channel() != null ? channel().minorVersion() : Codec.minorVersion());
    }
    
    /**
     * When a {@link ReactorChannel} becomes active for a client or server, this is
     * populated with the protocolType associated with the content being sent on
     * this connection. If the protocolType indicated by a server does not match
     * the protocolType that a client specifies, the connection will be
     * rejected. The transport layer is data neutral and does not change nor
     * depend on any information in content being distributed. This information
     * is provided to help client and server applications manage the information
     * they are communicating.
     * 
     * @return the protocolType
     */
    public int protocolType()
    {
        return channel().protocolType();
    }
        
    TunnelStreamReadInfo readInfo()
    {
        return _readInfo;
    }
    
    /**
     * The TunnelStreamManager associated with this ReactorChannel.
     * 
     * @return the TunnelManager
     */
    public TunnelManager tunnelManager()
    {
        return _tunnelManager;
    }
    
    /**
     * Open a tunnel stream for a ReactorChannel. Used by TunnelStream consumers.
     * Once the TunnelStream is created, use it to send messages to and receive messages
     * from the tunnel stream.
     * 
     * @param options the options for opening the tunnel stream
     * @param errorInfo error structure to be populated in the event of failure
     *  
     * @return {@link ReactorReturnCodes} indicating success or failure
     * 
     * @see TunnelStreamOpenOptions
     */
    public int openTunnelStream(TunnelStreamOpenOptions options, ReactorErrorInfo errorInfo)
    {
        if (errorInfo == null)
            return ReactorReturnCodes.FAILURE;
        else if (options == null)
            return reactor().populateErrorInfo(errorInfo, ReactorReturnCodes.FAILURE,
                                               "ReactorChannel.openTunnelStream",
                                               "TunnelStreamOpenOptions cannot be null");
        else if (options.statusEventCallback() == null)
            return _reactor.populateErrorInfo(errorInfo, ReactorReturnCodes.FAILURE,
                                              "ReactorChannel.openTunnelStream",
                                              "TunnelStream statusEventCallback must be specified");            
        else if (options.defaultMsgCallback() == null)
            return _reactor.populateErrorInfo(errorInfo, ReactorReturnCodes.FAILURE,
                                              "ReactorChannel.openTunnelStream",
                                              "TunnelStream defaultMsgCallback must be specified");            
        else if (_reactor.isShutdown())
            return _reactor.populateErrorInfo(errorInfo, ReactorReturnCodes.FAILURE,
                                              "ReactorChannel.openTunnelStream",
                                              "Reactor is shutdown, openTunnelStream aborted");
        
        _reactor._reactorLock.lock();
        try
        {
            // validate class of service first
            boolean isServer = (_server != null ? true : false);
            if (!options.classOfService().isValid(isServer, errorInfo))
            {
                return ReactorReturnCodes.FAILURE;                
            }
            
            if (_streamIdtoTunnelStreamTable.containsKey(options.streamId()))
            {
                return _reactor.populateErrorInfo(errorInfo, ReactorReturnCodes.FAILURE,
                                                  "ReactorChannel.openTunnelStream",
                                                  "TunnelStream is already open for stream id " + options.streamId());
            }
            
            // open tunnel stream
            TunnelStream tunnelStream = new TunnelStream(this, options);
            _streamIdtoTunnelStreamTable.put(tunnelStream.streamId(), tunnelStream);
            if (tunnelStream.tunnelStreamHandler().openStream(tunnelStream, errorInfo.error()) < ReactorReturnCodes.SUCCESS)
            {
                return _reactor.populateErrorInfo(errorInfo, ReactorReturnCodes.FAILURE,
                                                  "ReactorChannel.openTunnelStream",
                                                  "tunnelStream.openStream() failed");
            }
            
            // send worker event for open response timeout
            long timeout = (options.responseTimeout() * NANO_PER_SEC) + System.nanoTime();
            if (!_reactor.sendWorkerEvent(WorkerEventTypes.START_OPEN_TIMER, this, tunnelStream, timeout))
            {
                // _reactor.sendWorkerEvent() failed, send channel down
                _reactor.sendWorkerEvent(WorkerEventTypes.CHANNEL_DOWN, this);
                _state = State.DOWN;
                _reactor.sendAndHandleChannelEventCallback("TunnelStream.dispatchChannel",
                                                      ReactorChannelEventTypes.CHANNEL_DOWN,
                                                      this, errorInfo);
                return _reactor.populateErrorInfo(errorInfo,
                                  ReactorReturnCodes.FAILURE,
                                  "TunnelStream.submit",
                                  "_reactor.sendWorkerEvent() failed");
            }

            // send a WorkerEvent to the Worker to immediately expire a timer
            if (_tunnelManager.needsDispatchNow())
            {
                if (!_reactor.sendDispatchNowEvent(this))
                {
                    // _reactor.sendDispatchNowEvent() failed, send channel down
                    _reactor.sendWorkerEvent(WorkerEventTypes.CHANNEL_DOWN, this);
                    _state = State.DOWN;
                    _reactor.sendAndHandleChannelEventCallback("ReactorChannel.openTunnelStream",
                                                          ReactorChannelEventTypes.CHANNEL_DOWN,
                                                          this, errorInfo);
                    return _reactor.populateErrorInfo(errorInfo,
                                      ReactorReturnCodes.FAILURE,
                                      "ReactorChannel.openTunnelStream",
                                      "_reactor.sendDispatchNowEvent() failed");
                }
            }
        }
        finally
        {
            _reactor._reactorLock.unlock();
        }
            
        return ReactorReturnCodes.SUCCESS;
    }
    
    /**
     * Accept a tunnel stream for a ReactorChannel. Used by TunnelStream providers.
     * Once the TunnelStream is accepted, use it to send messages to and receive messages
     * from the tunnel stream.
     * 
     * @param event the request information of the tunnel stream request to accept
     * @param options the options for accepting the tunnel stream
     * @param errorInfo error structure to be populated in the event of failure
     *  
     * @return {@link ReactorReturnCodes} indicating success or failure
     * 
     * @see TunnelStreamRequestEvent
     * @see TunnelStreamAcceptOptions
     */
    public int acceptTunnelStream(TunnelStreamRequestEvent event, TunnelStreamAcceptOptions options, ReactorErrorInfo errorInfo)
    {
        int ret;
        
        if (errorInfo == null)
            return ReactorReturnCodes.FAILURE;
        else if (event == null)
            return reactor().populateErrorInfo(errorInfo, ReactorReturnCodes.FAILURE,
                                               "ReactorChannel.acceptTunnelStream",
                                               "TunnelStreamRequestEvent cannot be null");
        else if (options == null)
            return reactor().populateErrorInfo(errorInfo, ReactorReturnCodes.FAILURE,
                                               "ReactorChannel.acceptTunnelStream",
                                               "TunnelStreamAcceptOptions cannot be null");
        else if (options.statusEventCallback() == null)
            return _reactor.populateErrorInfo(errorInfo, ReactorReturnCodes.FAILURE,
                                              "ReactorChannel.acceptTunnelStream",
                                              "TunnelStream statusEventCallback must be specified");            
        else if (options.defaultMsgCallback() == null)
            return _reactor.populateErrorInfo(errorInfo, ReactorReturnCodes.FAILURE,
                                              "ReactorChannel.acceptTunnelStream",
                                              "TunnelStream defaultMsgCallback must be specified");            
        else if (_reactor.isShutdown())
            return _reactor.populateErrorInfo(errorInfo, ReactorReturnCodes.FAILURE,
                                              "ReactorChannel.acceptTunnelStream",
                                              "Reactor is shutdown, acceptTunnelStream aborted");

        _reactor._reactorLock.lock();
        try
        {
            // send reject if stream versions don't match
            if (event.classOfService().common().streamVersion() != CosCommon.CURRENT_STREAM_VERSION)
            {
                _tunnelStreamRejectOptions.clear();
                _tunnelStreamRejectOptions.state().streamState(StreamStates.CLOSED);
                _tunnelStreamRejectOptions.state().dataState(DataStates.SUSPECT);
                _tunnelStreamRejectOptions.state().text().data("Unsupported class of service stream version: " + event.classOfService().common().streamVersion());
                _tunnelStreamRejectOptions.expectedClassOfService(_defaultClassOfService);
                
                rejectTunnelStream(event, _tunnelStreamRejectOptions, errorInfo);
                
                return _reactor.populateErrorInfo(errorInfo,
                                                  ReactorReturnCodes.FAILURE,
                                                  "ReactorChannel.acceptTunnelStream",
                                                  "Unsupported class of service stream version: " + event.classOfService().common().streamVersion());
            }
            
            // send reject if request's class of service couldn't be fully decoded
            if (!event.classOfService().decodedProperly())
            {
                _tunnelStreamRejectOptions.clear();
                _tunnelStreamRejectOptions.state().streamState(StreamStates.CLOSED);
                _tunnelStreamRejectOptions.state().dataState(DataStates.SUSPECT);
                _tunnelStreamRejectOptions.state().text().data("Requested class of service could not be fully decoded");
                _tunnelStreamRejectOptions.expectedClassOfService(_defaultClassOfService);
                
                rejectTunnelStream(event, _tunnelStreamRejectOptions, errorInfo);
                
                return _reactor.populateErrorInfo(errorInfo,
                                                  ReactorReturnCodes.FAILURE,
                                                  "ReactorChannel.acceptTunnelStream",
                                                  "Requested class of service could not be fully decoded");
            }          
            
            // validate accepted class of service
            boolean isServer = (_server != null ? true : false);
            if (!options.classOfService().isValid(isServer, errorInfo))
            {
                return _reactor.populateErrorInfo(errorInfo,
                                                  ReactorReturnCodes.FAILURE,
                                                  "ReactorChannel.acceptTunnelStream",
                                                  "Accepted class of service is invalid");
            }
            
            // send TunnelStream refresh message
            _tunnelStreamRespMsg.clear();
            _tunnelStreamRespMsg.msgClass(MsgClasses.REFRESH);
            RefreshMsg refreshMsg = (RefreshMsg)_tunnelStreamRespMsg;
            
            refreshMsg.applyPrivateStream();
            refreshMsg.applyQualifiedStream();
            refreshMsg.applySolicited();
            refreshMsg.applyRefreshComplete();
            refreshMsg.applyDoNotCache();
            refreshMsg.domainType(event.domainType());
            refreshMsg.streamId(event.streamId());
            refreshMsg.applyHasMsgKey();
            refreshMsg.msgKey().applyHasServiceId();
            refreshMsg.msgKey().serviceId(event.serviceId());
            refreshMsg.msgKey().applyHasName();
            refreshMsg.msgKey().name().data(event.name());
            refreshMsg.state().streamState(StreamStates.OPEN);
            refreshMsg.state().dataState(DataStates.OK);
            refreshMsg.state().code(StateCodes.NONE);
            refreshMsg.state().text().data("Successful open of TunnelStream: " + event.name());
            refreshMsg.containerType(DataTypes.FILTER_LIST);
            refreshMsg.msgKey().applyHasFilter();
            refreshMsg.msgKey().filter(options.classOfService().filterFlags());
            
            refreshMsg.encodedDataBody(options.classOfService().encode(this));
            
            _reactorSubmitOptions.clear();
            while ((ret = submit(refreshMsg, _reactorSubmitOptions, errorInfo)) < ReactorReturnCodes.SUCCESS)
            {
                if (ret != ReactorReturnCodes.NO_BUFFERS)
                {
                    return ret;
                }
                else // out of buffers
                {
                    // increase buffers
                    _reactorChannelInfo.clear();
                    if ((ret = info(_reactorChannelInfo, errorInfo)) < ReactorReturnCodes.SUCCESS)
                    {
                        return ret;
                    }
                    int newNumberOfBuffers = _reactorChannelInfo.channelInfo().guaranteedOutputBuffers() + 10;
                    if ((ret = ioctl(IoctlCodes.NUM_GUARANTEED_BUFFERS, newNumberOfBuffers, errorInfo)) < ReactorReturnCodes.SUCCESS)
                    {
                        return ret;
                    }
                }
            }

            // open tunnel stream
            TunnelStream tunnelStream = new TunnelStream(this, event, options);
            _streamIdtoTunnelStreamTable.put(tunnelStream.streamId(), tunnelStream);
            if (tunnelStream.tunnelStreamHandler().openStream(tunnelStream, errorInfo.error()) < ReactorReturnCodes.SUCCESS)
            {
                return _reactor.populateErrorInfo(errorInfo, ReactorReturnCodes.FAILURE,
                                                  "ReactorChannel.acceptTunnelStream",
                                                  "tunnelStream.openStream() failed");
            }
            
            // send a WorkerEvent to the Worker to immediately expire a timer
            if (_tunnelManager.needsDispatchNow())
            {
                if (!_reactor.sendDispatchNowEvent(this))
                {
                    // _reactor.sendDispatchNowEvent() failed, send channel down
                    _reactor.sendWorkerEvent(WorkerEventTypes.CHANNEL_DOWN, this);
                    _state = State.DOWN;
                    _reactor.sendAndHandleChannelEventCallback("ReactorChannel.acceptTunnelStream",
                                                          ReactorChannelEventTypes.CHANNEL_DOWN,
                                                          this, errorInfo);
                    return _reactor.populateErrorInfo(errorInfo,
                                      ReactorReturnCodes.FAILURE,
                                      "ReactorChannel.acceptTunnelStream",
                                      "_reactor.sendDispatchNowEvent() failed");
                }
            }
            
            return _reactor.sendAndHandleTunnelStreamStatusEventCallback("ReactorChannel.acceptTunnelStream",
                                                                         this,
                                                                         tunnelStream,
                                                                         null,
                                                                         refreshMsg,
                                                                         refreshMsg.state(),
                                                                         errorInfo);
        }
        finally
        {
            _reactor._reactorLock.unlock();
        }
    }

    /**
     * Reject a tunnel stream for a ReactorChannel. Used by TunnelStream providers.
     * 
     * @param event the request information of the tunnel stream request to reject
     * @param options the options for rejecting the tunnel stream
     * @param errorInfo error structure to be populated in the event of failure
     *  
     * @return {@link ReactorReturnCodes} indicating success or failure
     * 
     * @see TunnelStreamRequestEvent
     */
    public int rejectTunnelStream(TunnelStreamRequestEvent event, TunnelStreamRejectOptions options, ReactorErrorInfo errorInfo)
    {
        int ret;
        
        if (errorInfo == null)
            return ReactorReturnCodes.FAILURE;
        else if (event == null)
            return reactor().populateErrorInfo(errorInfo, ReactorReturnCodes.FAILURE,
                                               "ReactorChannel.rejectTunnelStream",
                                               "TunnelStreamRequestEvent cannot be null");
        else if (options == null)
            return reactor().populateErrorInfo(errorInfo, ReactorReturnCodes.FAILURE,
                                               "ReactorChannel.rejectTunnelStream",
                                               "options cannot be null");
        else if (_reactor.isShutdown())
            return _reactor.populateErrorInfo(errorInfo, ReactorReturnCodes.FAILURE,
                                              "ReactorChannel.rejectTunnelStream",
                                              "Reactor is shutdown, rejectTunnelStream aborted");

        _reactor._reactorLock.lock();
        try
        {
            _tunnelStreamRespMsg.clear();
            _tunnelStreamRespMsg.msgClass(MsgClasses.STATUS);
            StatusMsg statusMsg = (StatusMsg)_tunnelStreamRespMsg;
            
            statusMsg.applyPrivateStream();
            statusMsg.applyQualifiedStream();
            statusMsg.domainType(event.domainType());
            statusMsg.streamId(event.streamId());
            statusMsg.applyHasMsgKey();
            statusMsg.msgKey().applyHasServiceId();
            statusMsg.msgKey().serviceId(event.serviceId());
            statusMsg.msgKey().applyHasName();
            statusMsg.msgKey().name().data(event.name());
            statusMsg.applyHasState();
            options.state().copy(statusMsg.state());
            if (options.expectedClassOfService() != null)
            {
                statusMsg.containerType(DataTypes.FILTER_LIST);
                statusMsg.msgKey().applyHasFilter();
                statusMsg.msgKey().filter(options.expectedClassOfService().filterFlags());
                statusMsg.encodedDataBody(options.expectedClassOfService().encode(this));
            }
            
            _reactorSubmitOptions.clear();
            while ((ret = submit(statusMsg, _reactorSubmitOptions, errorInfo)) < ReactorReturnCodes.SUCCESS)
            {
                if (ret != ReactorReturnCodes.NO_BUFFERS)
                {
                    return ret;
                }
                else // out of buffers
                {
                    // increase buffers
                    _reactorChannelInfo.clear();
                    if ((ret = info(_reactorChannelInfo, errorInfo)) < ReactorReturnCodes.SUCCESS)
                    {
                        return ret;
                    }
                    int newNumberOfBuffers = _reactorChannelInfo.channelInfo().guaranteedOutputBuffers() + 10;
                    if ((ret = ioctl(IoctlCodes.NUM_GUARANTEED_BUFFERS, newNumberOfBuffers, errorInfo)) < ReactorReturnCodes.SUCCESS)
                    {
                        return ret;
                    }
                }
            }
        }
        finally
        {
            _reactor._reactorLock.unlock();
        }
        
        return ReactorReturnCodes.SUCCESS;
    }

    HashMap<Integer,TunnelStream> streamIdtoTunnelStreamTable()
    {
        return _streamIdtoTunnelStreamTable;
    }
}