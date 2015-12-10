package com.thomsonreuters.upa.valueadd.domainrep.rdm.dictionary;

/**
 * The types of RDM Dictionary Messages. rdmMsgType member in
 * {@link DictionaryMsg} may be set to one of these to indicate the specific
 * RDMDictionaryMsg class.
 * 
 * @see DictionaryMsg
 * @see DictionaryRequest
 * @see DictionaryClose
 * @see DictionaryRefresh
 * @see DictionaryStatus
 */
public enum DictionaryMsgType
{
    /**
     * (0) Unknown type.
     */
    UNKNOWN(0),
    /**
     * (1) Dictionary Request.
     */
    REQUEST(1),
    /**
     * (2) Dictionary Close.
     */
    CLOSE(2),
    /**
     * (3) Dictionary Close.
     */
    REFRESH(3),
    /**
     * (4) Dictionary Close.
     */
    STATUS(4);

    private DictionaryMsgType(int value)
    {
        this.value = value;
    }

    @SuppressWarnings("unused")
    private int value;
}
