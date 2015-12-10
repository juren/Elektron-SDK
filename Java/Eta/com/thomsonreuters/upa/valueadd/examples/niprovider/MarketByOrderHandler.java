package com.thomsonreuters.upa.valueadd.examples.niprovider;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import com.thomsonreuters.upa.codec.CodecFactory;
import com.thomsonreuters.upa.codec.CodecReturnCodes;
import com.thomsonreuters.upa.codec.DataDictionary;
import com.thomsonreuters.upa.codec.DataStates;
import com.thomsonreuters.upa.codec.EncodeIterator;
import com.thomsonreuters.upa.codec.FieldEntry;
import com.thomsonreuters.upa.codec.FieldList;
import com.thomsonreuters.upa.codec.Msg;
import com.thomsonreuters.upa.codec.QosRates;
import com.thomsonreuters.upa.codec.QosTimeliness;
import com.thomsonreuters.upa.codec.StateCodes;
import com.thomsonreuters.upa.codec.StreamStates;
import com.thomsonreuters.upa.valueadd.examples.common.CacheHandler;
import com.thomsonreuters.upa.valueadd.examples.common.CacheInfo;
import com.thomsonreuters.upa.valueadd.examples.niprovider.StreamIdWatchList.WatchListEntry;
import com.thomsonreuters.upa.valueadd.reactor.ReactorChannel;
import com.thomsonreuters.upa.valueadd.reactor.ReactorErrorInfo;
import com.thomsonreuters.upa.valueadd.reactor.ReactorFactory;
import com.thomsonreuters.upa.valueadd.reactor.ReactorReturnCodes;
import com.thomsonreuters.upa.valueadd.reactor.ReactorSubmitOptions;
import com.thomsonreuters.upa.examples.rdm.marketbyorder.MarketByOrderClose;
import com.thomsonreuters.upa.examples.rdm.marketbyorder.MarketByOrderRefresh;
import com.thomsonreuters.upa.examples.rdm.marketbyorder.MarketByOrderResponseBase;
import com.thomsonreuters.upa.examples.rdm.marketbyorder.MarketByOrderUpdate;
import com.thomsonreuters.upa.rdm.DomainTypes;
import com.thomsonreuters.upa.transport.TransportBuffer;


/*
 * This is the market by order handler for the UPA Value Add NIProvider application. It
 * provides methods to encode and send refreshes and updates, as well as
 * closing streams.
 */
class MarketByOrderHandler
{
    public static int TRANSPORT_BUFFER_SIZE_REQUEST = 1024;
    public static int TRANSPORT_BUFFER_SIZE_CLOSE = 1024;

    private int domainType;

    private MarketByOrderRefresh marketByOrderRefresh;
    private MarketByOrderUpdate marketByOrderUpdate;
    private MarketByOrderClose closeMessage;

    private final StreamIdWatchList watchList; // stream states based on
                                               // response

    // reusable variables used for encoding
    protected FieldList fieldList = CodecFactory.createFieldList();
    protected FieldEntry fieldEntry = CodecFactory.createFieldEntry();
    private EncodeIterator encIter = CodecFactory.createEncodeIterator();
    
    private ReactorSubmitOptions submitOptions = ReactorFactory.createReactorSubmitOptions();
    
    public MarketByOrderHandler(StreamIdWatchList watchList, DataDictionary dictionary)
    {
        this(watchList, DomainTypes.MARKET_BY_ORDER, dictionary);
    }

    protected MarketByOrderHandler(StreamIdWatchList watchList, int domainType, DataDictionary dictionary)
    {
        this.watchList = watchList;
        this.domainType = domainType;
        marketByOrderRefresh = createMarketByOrderRefresh();
        marketByOrderRefresh.dictionary(dictionary);
        marketByOrderUpdate = createMarketByOrderUpdate();;
        marketByOrderUpdate.dictionary(dictionary);
        closeMessage = new MarketByOrderClose();
    }

    protected MarketByOrderRefresh createMarketByOrderRefresh()
    {
        return new MarketByOrderRefresh();
    }
    
    protected MarketByOrderUpdate createMarketByOrderUpdate()
    {
        return new MarketByOrderUpdate();
    }
    
    private int closeStream(ReactorChannel chnl, int streamId, ReactorErrorInfo errorInfo)
    {
        //get a buffer for the item close
        TransportBuffer msgBuf = chnl.getBuffer(TRANSPORT_BUFFER_SIZE_CLOSE, false, errorInfo);
        if (msgBuf == null)
            return ReactorReturnCodes.FAILURE;

        //encode item close
        closeMessage.clear();
        closeMessage.streamId(streamId);
        encIter.clear();
        encIter.setBufferAndRWFVersion(msgBuf, chnl.majorVersion(), chnl.minorVersion());

        int ret = closeMessage.encode(encIter);
        if (ret != CodecReturnCodes.SUCCESS)
        {
            System.out.println("encodeMarketByOrderClose(): Failed <"
                        + CodecReturnCodes.toString(ret) + ">");
        }
        return chnl.submit(msgBuf, submitOptions, errorInfo);
    }

    /*
     * Close all item streams.
     */
    int closeStreams(ReactorChannel chnl, ReactorErrorInfo errorInfo)
    {
        Iterator<Entry<Integer, WatchListEntry>> iter = watchList.iterator();
        while(iter.hasNext())
        {
            Map.Entry<Integer, WatchListEntry> entry = iter.next();
         
            if (entry.getValue().domainType != domainType)
            {
                /* this entry is from a different domainType, skip */
                continue;
            }

            closeStream(chnl, entry.getKey().intValue(), errorInfo);
            iter.remove();
        }

        watchList.clear();

        return ReactorReturnCodes.SUCCESS;
    }
    
    /*
     * Encodes and sends item refreshes for market by order domain.
     */
    int sendItemRefreshes(ReactorChannel chnl, List<String> itemNames, int serviceId, CacheInfo cacheInfo,
            ReactorErrorInfo errorInfo)
    {
        if (itemNames == null || itemNames.isEmpty())
            return ReactorReturnCodes.SUCCESS;

        generateRefreshAndUpdate(serviceId);

        return sendRefreshes(chnl, itemNames, cacheInfo, errorInfo);
    }

    private void generateRefreshAndUpdate(int serviceId)
    {
        //refresh complete
        marketByOrderRefresh.applyRefreshComplete();

        //service Id
        marketByOrderRefresh.serviceId(serviceId);
        marketByOrderRefresh.applyHasServiceId();
        marketByOrderUpdate.serviceId(serviceId);
        marketByOrderUpdate.applyHasServiceId();

        //QoS
        marketByOrderRefresh.qos().dynamic(false);
        marketByOrderRefresh.qos().timeliness(QosTimeliness.REALTIME);
        marketByOrderRefresh.qos().rate(QosRates.TICK_BY_TICK);
        marketByOrderRefresh.applyHasQos();

        //state
        marketByOrderRefresh.state().streamState(StreamStates.OPEN);
        marketByOrderRefresh.state().dataState(DataStates.OK);
        marketByOrderRefresh.state().code(StateCodes.NONE);
        marketByOrderRefresh.state().text().data("Item Refresh Completed");
    }

    private int sendRefreshes(ReactorChannel chnl, List<String> itemNames, CacheInfo cacheInfo, ReactorErrorInfo errorInfo)
    {
        int ret = ReactorReturnCodes.SUCCESS;
        for (String itemName : itemNames)
        {
            Integer streamId = watchList.add(domainType, itemName);
           
            marketByOrderRefresh.itemName().data(itemName);
            marketByOrderRefresh.streamId(streamId);
            marketByOrderRefresh
                    .marketByOrderItem(watchList.get(streamId).marketByOrderItem);

            ret = encodeAndSendContent(chnl, marketByOrderRefresh, cacheInfo, watchList.get(streamId), true, errorInfo);
            if (ret < ReactorReturnCodes.SUCCESS)
                return ret;
        }

        return ReactorReturnCodes.SUCCESS;
    }

    /*
     * Encodes and sends item updates for market by order domain.
     */
    int sendItemUpdates(ReactorChannel chnl, CacheInfo cacheInfo, ReactorErrorInfo errorInfo)
    {
        int ret = ReactorReturnCodes.SUCCESS;
    	int itemCount = 0;
        for (Entry<Integer, WatchListEntry> mapEntry : watchList)
        {
            WatchListEntry wle = mapEntry.getValue();
            if (mapEntry.getValue().domainType != domainType)
            {
                /* this entry is from a different domainType, skip */
                continue;
            }
            /* update fields */
            wle.marketByOrderItem.updateFields();

            marketByOrderUpdate.streamId(mapEntry.getKey().intValue());
            marketByOrderUpdate.itemName().data(wle.itemName);
            marketByOrderUpdate.marketByOrderItem(wle.marketByOrderItem);

            ret = encodeAndSendContent(chnl, marketByOrderUpdate, cacheInfo, wle, false, errorInfo);
            if (ret < ReactorReturnCodes.SUCCESS)
                return ret;
            itemCount++;
        }
        
        if ( itemCount > 0)
        	System.out.println("Sent " + itemCount + " MarketByOrder items.");
        return ret;
    }

    private int encodeAndSendContent(ReactorChannel chnl, MarketByOrderResponseBase marketContent,
            CacheInfo cacheInfo, WatchListEntry wle, boolean isRefresh, ReactorErrorInfo errorInfo)
    {
        //get a buffer for the item request
        TransportBuffer msgBuf = chnl.getBuffer(TRANSPORT_BUFFER_SIZE_REQUEST, false,
                                                         errorInfo);

        if (msgBuf == null)
        {
            return CodecReturnCodes.FAILURE;
        }

        encIter.clear();
        encIter.setBufferAndRWFVersion(msgBuf, chnl.majorVersion(), chnl.minorVersion());

        if ( isRefresh && cacheInfo.useCache && wle.cacheEntry != null )
        {
        	Msg msg = marketContent.encodeMsg();
            int ret = msg.encodeInit(encIter, 0);
            if (ret < CodecReturnCodes.SUCCESS)
                return ret;

            ret = CacheHandler.retrieveFromCache(encIter, wle.cacheEntry, cacheInfo);
        	if (ret != CodecReturnCodes.SUCCESS)
        	{
        		errorInfo.error().text(" Error retrieving payload from cache : " + cacheInfo.cacheError.text());
        		return ret;
        	}
        	
            ret = msg.encodeComplete(encIter, true);
            if (ret < CodecReturnCodes.SUCCESS)
                return ret;
        }
        else
        {
	        int ret = marketContent.encode(encIter);
	        if (ret < CodecReturnCodes.SUCCESS)
	        {
	            errorInfo.error().text("MarketByOrderResponse.encode failed");
	            errorInfo.error().errorId(ret);
	            return ret;
	        }
	        else if (cacheInfo.useCache) 
	        {
	        	System.out.println("Applying item " + wle.itemName + " to cache.");
	        	if (wle.cacheEntry == null)
	        		wle.cacheEntry = CacheHandler.createCacheEntry(cacheInfo);
	        	ret = CacheHandler.applyMsgBufferToCache(encIter.majorVersion(), encIter.minorVersion(), 
	        										wle.cacheEntry, cacheInfo, msgBuf);
	        	if (ret != CodecReturnCodes.SUCCESS)
	        		 errorInfo.error().text(" Error Applying payload to cache : " + cacheInfo.cacheError.text());
	        }
        }
        return chnl.submit(msgBuf, submitOptions, errorInfo);
    }

}
