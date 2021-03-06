/*
 * 
 */
package com.zimbra.cs.dav.property;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.dom4j.Element;
import org.dom4j.QName;
import org.dom4j.tree.DefaultElement;

import com.zimbra.common.util.HttpUtil;
import com.zimbra.cs.dav.DavContext;
import com.zimbra.cs.dav.DavElements;

/**
 * RFC 2518bis section 4
 * 
 * @author jylee
 *
 */
public class ResourceProperty {
	private boolean mProtected;
	private boolean mVisible;
	private boolean mLive;
	private QName mName;
	private Locale mLocale;
	private String mValue;
	protected ArrayList<Element> mChildren;

	public ResourceProperty(String name) {
		this(QName.get(name, DavElements.WEBDAV_NS));
	}
	
	public ResourceProperty(QName name) {
		mName = name;
		mChildren = new ArrayList<Element>();
	}

	public ResourceProperty(Element elem) {
		this(elem.getQName());
		mValue = elem.getText();
		for (Object o : elem.elements())
			if (o instanceof Element)
				mChildren.add((Element) o);
	}
	
	/* Returns qualified name for the property. */
	public QName getName() {
		return mName;
	}
	
	/* Returns true if the property is protected. */
	public boolean isProtected() {
		return mProtected;
	}
	
	/* Returns true if the property is to be returned in allprop request. */
	public boolean isVisible() {
		return mVisible;
	}
	
	/* Returns true if the property is live. */
	public boolean isLive() {
		// TODO: implement
		return mLive;
	}
	
	/* Transform the property to Element, attached to the parent. */
	public Element toElement(DavContext ctxt, Element parent, boolean nameOnly) {
		Element elem = parent.addElement(mName);
		if (nameOnly)
			return elem;
		
		if (mValue != null) {
			if (mLocale != null)
				elem.addAttribute(DavElements.E_LANG, mLocale.toString());
			elem.setText(mValue);
		} else
			for (Element child : mChildren)
				elem.add(child.createCopy());
		return elem;
	}
	
	/* Sets the Locale for the text part. */
	public void setMessageLocale(Locale locale) {
		mLocale = locale;
	}
	
	/* Sets the property value. */
	public void setStringValue(String value) {
		mValue = value;
	}
	
	/* Returns the text portion of property value. */
	public String getStringValue() {
		return mValue;
	}

	/* Adds child Element. */
	public Element addChild(QName e) {
		Element child = new DefaultElement(e);
		mChildren.add(child);
		return child;
	}
	
	/* Returns the child Elements. */
	public List<Element> getChildren() {
		return mChildren;
	}
	
	public void setProtected(boolean pr) {
		mProtected = pr;
		mVisible = !pr;  // by default protected == not visible
	}

	public void setVisible(boolean v) {
		mVisible = v;
	}
	
	protected Element createHref(String path) {
		Element e = org.dom4j.DocumentHelper.createElement(DavElements.E_HREF);
		e.setText(HttpUtil.urlEscape(path));
		return e;
	}
	
	public String toString() {
		return "ResourceProperty: " + mName + ((mValue != null) ? ": '" + mValue + "'" : "");
	}
}
