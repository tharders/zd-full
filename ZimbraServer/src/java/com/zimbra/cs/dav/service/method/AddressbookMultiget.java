/*
 * 
 */
package com.zimbra.cs.dav.service.method;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;

import javax.servlet.http.HttpServletResponse;

import org.dom4j.Element;

import com.zimbra.common.service.ServiceException;
import com.zimbra.common.mime.MimeConstants;
import com.zimbra.cs.dav.DavContext;
import com.zimbra.cs.dav.DavElements;
import com.zimbra.cs.dav.DavException;
import com.zimbra.cs.dav.DavContext.RequestProp;
import com.zimbra.cs.dav.resource.AddressbookCollection;
import com.zimbra.cs.dav.resource.DavResource;
import com.zimbra.cs.dav.resource.UrlNamespace;
import com.zimbra.cs.dav.service.DavResponse;

public class AddressbookMultiget extends Report {
    public void handle(DavContext ctxt) throws ServiceException, DavException {
        Element query = ctxt.getRequestMessage().getRootElement();
        if (!query.getQName().equals(DavElements.CardDav.E_ADDRESSBOOK_MULTIGET))
            throw new DavException("msg "+query.getName()+" is not addressbook-multiget", HttpServletResponse.SC_BAD_REQUEST, null);

        DavResponse resp = ctxt.getDavResponse();
        DavResource reqResource = ctxt.getRequestedResource();
        if (!(reqResource instanceof AddressbookCollection))
            throw new DavException("requested resource is not an addressbook collection", HttpServletResponse.SC_BAD_REQUEST, null);
        RequestProp reqProp = ctxt.getRequestProp();
        for (Object obj : query.elements(DavElements.E_HREF)) {
            if (obj instanceof Element) {
                String href;
                try {
                    href = URLDecoder.decode(((Element)obj).getText(), MimeConstants.P_CHARSET_UTF8);
                } catch (UnsupportedEncodingException e) {
                    href = URLDecoder.decode(((Element)obj).getText());
                }
                DavResource rs = UrlNamespace.getResourceAtUrl(ctxt, href);
                if (rs != null)
                    resp.addResource(ctxt, rs, reqProp, false);
            }
        }
    }
}
