package com.thomsonreuters.upa.valueadd.domainrep.rdm.dictionary;

import java.nio.ByteBuffer;

import com.thomsonreuters.upa.codec.Buffer;
import com.thomsonreuters.upa.codec.CodecFactory;
import com.thomsonreuters.upa.codec.CodecReturnCodes;
import com.thomsonreuters.upa.codec.DataTypes;
import com.thomsonreuters.upa.codec.DecodeIterator;
import com.thomsonreuters.upa.codec.EncodeIterator;
import com.thomsonreuters.upa.codec.Msg;
import com.thomsonreuters.upa.codec.MsgClasses;
import com.thomsonreuters.upa.codec.MsgKey;
import com.thomsonreuters.upa.codec.RequestMsgFlags;
import com.thomsonreuters.upa.codec.RequestMsg;
import com.thomsonreuters.upa.rdm.Dictionary;
import com.thomsonreuters.upa.rdm.DomainTypes;
import com.thomsonreuters.upa.valueadd.domainrep.rdm.MsgBaseImpl;

class DictionaryRequestImpl extends MsgBaseImpl
{
    private Buffer dictionaryName;
    private int verbosity;
    private int serviceId;
    private int flags;

    private RequestMsg requestMsg = (RequestMsg)CodecFactory.createMsg();
    private final static String eol = "\n";
    private final static String tab = "\t";
    
    DictionaryRequestImpl()
    {
        dictionaryName = CodecFactory.createBuffer();
        clear();
    }

    public void clear()
    {
        super.clear();
        flags = 0;
        serviceId = 0;
        dictionaryName.clear();
        verbosity = Dictionary.VerbosityValues.NORMAL;
    }

    public int decode(DecodeIterator dIter, Msg msg)
    {
        clear();
        if (msg.msgClass() != MsgClasses.REQUEST)
            return CodecReturnCodes.FAILURE;
        RequestMsg requestMsg = (RequestMsg)msg;
        streamId(requestMsg.streamId());
        MsgKey msgKey = msg.msgKey();
        if (requestMsg.checkStreaming())
            applyStreaming();
        if (msgKey == null)
            return CodecReturnCodes.FAILURE;
        if (!msgKey.checkHasFilter())
            return CodecReturnCodes.FAILURE;
        if (!msgKey.checkHasServiceId())
            return CodecReturnCodes.FAILURE;
        if (!msgKey.checkHasName())
            return CodecReturnCodes.FAILURE;

        serviceId(msgKey.serviceId());
        Buffer name = msgKey.name();
        dictionaryName().data(name.data(), name.position(), name.length());

        verbosity((int)msgKey.filter());
        return CodecReturnCodes.SUCCESS;
    }

    public int encode(EncodeIterator encodeIter)
    {
        requestMsg.clear();
        requestMsg.streamId(streamId());
        requestMsg.msgClass(MsgClasses.REQUEST);
        requestMsg.domainType(DomainTypes.DICTIONARY);
        requestMsg.containerType(DataTypes.NO_DATA);
        requestMsg.flags(RequestMsgFlags.NONE);
        requestMsg.msgKey().applyHasFilter();
        requestMsg.msgKey().applyHasName();
        requestMsg.msgKey().applyHasServiceId();
        if (checkStreaming())
            requestMsg.applyStreaming();
        requestMsg.msgKey().name(dictionaryName());
        requestMsg.msgKey().serviceId(serviceId());
        requestMsg.msgKey().filter(verbosity());
        return requestMsg.encode(encodeIter);
    }

    public int verbosity()
    {
        return verbosity;
    }

    public void verbosity(int verbosity)
    {
        this.verbosity = verbosity;
    }

    public Buffer dictionaryName()
    {
        return dictionaryName;
    }

    public void dictionaryName(Buffer dictionaryName)
    {
        assert (dictionaryName != null) : "dictionaryName can not be null";
        this.dictionaryName.data(dictionaryName.data(), dictionaryName.position(), dictionaryName.length());
    }
    
    public int copy(DictionaryRequest destRequestMsg)
    {
        assert (destRequestMsg != null) : "destRequestMsg can not be null";

        destRequestMsg.streamId(streamId());
        destRequestMsg.serviceId(serviceId());
        destRequestMsg.verbosity(verbosity());

        ByteBuffer byteBuffer = ByteBuffer.allocate(this.dictionaryName.length());
        this.dictionaryName.copy(byteBuffer);
        destRequestMsg.dictionaryName().data(byteBuffer);

        if (checkStreaming())
        {
            destRequestMsg.applyStreaming();
        }

        return CodecReturnCodes.SUCCESS;
    }

    public int serviceId()
    {
        return serviceId;
    }

    public void serviceId(int serviceId)
    {
        this.serviceId = serviceId;
    }

    public int flags()
    {
        return flags;
    }

    public void flags(int flags)
    {
        this.flags = flags;
    }

    public void applyStreaming()
    {
        flags |= DictionaryRequestFlags.STREAMING;
    }

    public boolean checkStreaming()
    {
        return (this.flags & DictionaryRequestFlags.STREAMING) != 0;
    }

    public String toString()
    {
        StringBuilder stringBuf = super.buildStringBuffer();
        stringBuf.insert(0, "DictionaryRequest: \n");

        stringBuf.append(tab);
        stringBuf.append("serviceId: ");
        stringBuf.append(serviceId());
        stringBuf.append(eol);

        stringBuf.append(tab);
        stringBuf.append("dictionaryName: ");
        stringBuf.append(dictionaryName());
        stringBuf.append(eol);

        stringBuf.append(tab);
        stringBuf.append("streaming: ");
        stringBuf.append(checkStreaming());
        stringBuf.append(eol);

        stringBuf.append(tab);
        stringBuf.append("verbosity: ");
        boolean addOr = false;
        long verbosity = verbosity();
        if ((verbosity & Dictionary.VerbosityValues.INFO) != 0)
        {
            stringBuf.append("INFO");
            addOr = true;
        }
        if ((verbosity & Dictionary.VerbosityValues.MINIMAL) != 0)
        {
            if (addOr)
                stringBuf.append(" | ");
            stringBuf.append("MINIMAL");
            addOr = true;
        }
        if ((verbosity & Dictionary.VerbosityValues.NORMAL) != 0)
        {
            if (addOr)
                stringBuf.append(" | ");
            stringBuf.append("NORMAL");
            addOr = true;
        }
        if ((verbosity & Dictionary.VerbosityValues.VERBOSE) != 0)
        {
            if (addOr)
                stringBuf.append(" | ");
            stringBuf.append("VERBOSE");
            addOr = true;
        }
        stringBuf.append(eol);

        return stringBuf.toString();
    }
    
    @Override
    public int domainType()
    {
        return DomainTypes.DICTIONARY;
    }
}
