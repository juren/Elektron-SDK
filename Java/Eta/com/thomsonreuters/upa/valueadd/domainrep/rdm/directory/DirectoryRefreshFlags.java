package com.thomsonreuters.upa.valueadd.domainrep.rdm.directory;

/**
 * The RDM Directory Refresh Flags.
 * 
 * @see DirectoryRefresh
 */
public class DirectoryRefreshFlags
{
    /** (0x00) No flags set. */
    public static final int NONE =  0x00;   
    
    /** (0x01) Indicates presence of the serviceId member. */
    public static final int HAS_SERVICE_ID =  0x01; 
    
    /** (0x02) Indicates whether this Refresh is provided in response to a request. */
    public static final int SOLICITED = 0x02;   
    
    /** (0x04) Indicates presence of the sequenceNumber member. */
    public static final int HAS_SEQ_NUM = 0x04; 
    
    /** (0x08) Indicates whether the Consumer should clear any existing cached service information. */
    public static final int CLEAR_CACHE = 0x08;

    private DirectoryRefreshFlags()
    {
        throw new AssertionError();
    }
}