/*
 * 
 */
package com.zimbra.cs.zclient;

import com.zimbra.common.service.ServiceException;
import com.zimbra.common.soap.MailConstants;
import com.zimbra.cs.zclient.event.ZModifyEvent;
import com.zimbra.common.soap.Element;
import com.zimbra.common.mime.MimeConstants;
import org.json.JSONException;

public class ZDocument implements ZItem, ToZJSONObject {

	private boolean mIsWiki;
	private String mName;
	private String mId;
	private String mFolderId;
	private String mVersion;
	private String mEditor;
	private String mCreator;
	private String mRestUrl;
	private long mCreatedDate;
	private long mModifiedDate;
	private long mMetaDataChangedDate;
	private long mSize;
    private String mContentType;
	private String mTagIds;
    
    public ZDocument(Element e) throws ServiceException {
    	mIsWiki = "w".equals(e.getName());
    	mName = e.getAttribute(MailConstants.A_NAME);
    	mId = e.getAttribute(MailConstants.A_ID);
    	mFolderId = e.getAttribute(MailConstants.A_FOLDER);
    	mVersion = e.getAttribute(MailConstants.A_VERSION);
    	mEditor = e.getAttribute(MailConstants.A_LAST_EDITED_BY);
    	mCreator = e.getAttribute(MailConstants.A_CREATOR);
    	mRestUrl = e.getAttribute(MailConstants.A_REST_URL, null);
    	mCreatedDate = e.getAttributeLong(MailConstants.A_CREATED_DATE, 0) * 1000;
    	mModifiedDate = e.getAttributeLong(MailConstants.A_MODIFIED_DATE, 0) * 1000;
    	mMetaDataChangedDate = e.getAttributeLong(MailConstants.A_MODIFIED_DATE, 0) * 1000;
        mSize = e.getAttributeLong(MailConstants.A_SIZE,0);
        if(mIsWiki){
            mContentType = MimeConstants.CT_TEXT_HTML; //"text/html";
        }else{
            mContentType = e.getAttribute(MailConstants.A_CONTENT_TYPE);
        }
        mTagIds = e.getAttribute(MailConstants.A_TAGS, null);
    }

    public ZJSONObject toZJSONObject() throws JSONException {
        ZJSONObject zjo = new ZJSONObject();
        zjo.put("name", mName);
        zjo.put("id", mId);
        zjo.put("folderId", mFolderId);
        zjo.put("version", mVersion);
        zjo.put("editor", mEditor);
        zjo.put("createor", mCreator);
        zjo.put("restUrl", mRestUrl);
        zjo.put("createdDate", mCreatedDate);
        zjo.put("modifiedDate", mModifiedDate);
        zjo.put("metaDataChangedDate", mMetaDataChangedDate);
        zjo.put("size", mSize);
        zjo.put("contentType", mContentType);
        zjo.put("tags", mTagIds);
        return zjo;
    }

    public String toString() {
        return String.format("[ZDocument %s]", mId);
    }

    public String dump() {
        return ZJSONObject.toString(this);
    }

    public String getId() {
		return mId;
	}
	public String getName() {
		return mName;
	}
	public String getFolderId() {
		return mFolderId;
	}
	public String getVersion() {
		return mVersion;
	}
	public String getEditor() {
		return mEditor;
	}
	public String getCreator() {
		return mCreator;
	}
	public String getRestUrl() {
		return mRestUrl;
	}
	public long getCreatedDate() {
		return mCreatedDate;
	}
	public long getModifiedDate() {
		return mModifiedDate;
	}
	public long getMetaDataChangedDate() {
		return mMetaDataChangedDate;
	}
	public boolean isWiki() {
		return mIsWiki;
    }
    public String getContentType() {
        return mContentType;
    }
    public long getSize() {
        return mSize;
    }
    public String getTagIds() {
        return mTagIds;
    }
    
    public void modifyNotification(ZModifyEvent event) throws ServiceException {
		// TODO Auto-generated method stub
	}
}
