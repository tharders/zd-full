/*
 * 
 */

/*
 * Created on May 26, 2004
 */
package com.zimbra.cs.service.account;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.zimbra.common.service.ServiceException;
import com.zimbra.common.soap.AccountConstants;
import com.zimbra.common.soap.Element;
import com.zimbra.common.util.ZimbraLog;
import com.zimbra.cs.account.Account;
import com.zimbra.cs.account.AttributeClass;
import com.zimbra.cs.account.AttributeFlag;
import com.zimbra.cs.account.AttributeManager;
import com.zimbra.cs.account.AuthToken;
import com.zimbra.cs.account.AuthTokenException;
import com.zimbra.cs.account.Config;
import com.zimbra.cs.account.Cos;
import com.zimbra.cs.account.DataSource;
import com.zimbra.cs.account.Domain;
import com.zimbra.cs.account.Identity;
import com.zimbra.cs.account.Provisioning;
import com.zimbra.cs.account.Server;
import com.zimbra.cs.account.Signature;
import com.zimbra.cs.account.Zimlet;
import com.zimbra.cs.account.Provisioning.AccountBy;
import com.zimbra.cs.mailbox.Mailbox;
import com.zimbra.cs.service.UserServlet;
import com.zimbra.cs.service.admin.AdminAccessControl;
import com.zimbra.cs.session.Session;
import com.zimbra.cs.session.SoapSession;
import com.zimbra.cs.util.BuildInfo;
import com.zimbra.cs.zimlet.ZimletPresence;
import com.zimbra.cs.zimlet.ZimletUserProperties;
import com.zimbra.cs.zimlet.ZimletUtil;
import com.zimbra.soap.SoapEngine;
import com.zimbra.soap.ZimbraSoapContext;
import com.zimbra.soap.account.type.Prop;

/**
 * @author schemers
 */
public class GetInfo extends AccountDocumentHandler  {

    private enum Section {
        MBOX, PREFS, ATTRS, ZIMLETS, PROPS, IDENTS, SIGS, DSRCS, CHILDREN;

        static final Set<Section> all = new HashSet<Section>(Arrays.asList(Section.values()));

        static Section lookup(String value) throws ServiceException {
            try {
                return Section.valueOf(value.toUpperCase(Locale.ENGLISH).trim());
            } catch (IllegalArgumentException iae) {
                throw ServiceException.INVALID_REQUEST("unknown GetInfo section: " + value.trim(), null);
            }
        }
    }

    public Element handle(Element request, Map<String, Object> context) throws ServiceException {
        ZimbraSoapContext zsc = getZimbraSoapContext(context);
        Account account = getRequestedAccount(zsc);

        if (!canAccessAccount(zsc, account))
            throw ServiceException.PERM_DENIED("can not access account");

        // figure out the subset of data the caller wants (default to all data)
        String secstr = request.getAttribute(AccountConstants.A_SECTIONS, null);
        Set<Section> sections;
        if (secstr != null) {
            sections = new HashSet<Section>();
            for (String sec : secstr.split(","))
                sections.add(Section.lookup(sec));
        } else {
            sections = Section.all;
        }
        

        Element response = zsc.createElement(AccountConstants.GET_INFO_RESPONSE);
        response.addAttribute(AccountConstants.E_VERSION, BuildInfo.FULL_VERSION, Element.Disposition.CONTENT);
        response.addAttribute(AccountConstants.E_ID, account.getId(), Element.Disposition.CONTENT);
        response.addAttribute(AccountConstants.E_NAME, account.getUnicodeName(), Element.Disposition.CONTENT);
        try {
            response.addAttribute(AccountConstants.E_CRUMB, zsc.getAuthToken().getCrumb(), Element.Disposition.CONTENT);
        } catch (AuthTokenException e) {
            // shouldn't happen
            ZimbraLog.account.warn("can't generate crumb", e);
        }
        long lifetime = zsc.getAuthToken().getExpires() - System.currentTimeMillis();
        response.addAttribute(AccountConstants.E_LIFETIME, lifetime, Element.Disposition.CONTENT);

        Provisioning prov = Provisioning.getInstance();
        
        // bug 53770, return if the request is using a delegated authtoken issued to an admin account
        AuthToken authToken = zsc.getAuthToken();
        if (authToken.isDelegatedAuth()) {
            Account admin = prov.get(AccountBy.id, authToken.getAdminAccountId());
            if (admin != null) {
                boolean isAdmin = AdminAccessControl.isAdequateAdminAccount(admin);
                if (isAdmin) {
                    response.addAttribute(AccountConstants.E_ADMIN_DELEGATED, true, Element.Disposition.CONTENT);
                }
            }
        }
        
        try {
            
            Server server = prov.getLocalServer();
            if (server != null)
                response.addAttribute(AccountConstants.A_DOCUMENT_SIZE_LIMIT, server.getFileUploadMaxSize());
            Config config = prov.getConfig();
            if (config != null) {
                response.addAttribute(AccountConstants.A_ATTACHMENT_SIZE_LIMIT, config.getMtaMaxMessageSize());
                response.addAttribute(AccountConstants.A_DISABLE_PASSWORD_LOCK, config.getAttr(AccountConstants.A_DISABLE_PASSWORD_LOCK));
            }
        } catch (ServiceException e) {}
        
        if (sections.contains(Section.MBOX) && Provisioning.onLocalServer(account)) {
            response.addAttribute(AccountConstants.E_REST, UserServlet.getRestUrl(account), Element.Disposition.CONTENT);
            try {
                Mailbox mbox = getRequestedMailbox(zsc);
                response.addAttribute(AccountConstants.E_QUOTA_USED, mbox.getSize(), Element.Disposition.CONTENT);

                Session s = (Session) context.get(SoapEngine.ZIMBRA_SESSION);
                if (s instanceof SoapSession) {
                    // we have a valid session; get the stats on this session
                    response.addAttribute(AccountConstants.E_PREVIOUS_SESSION, ((SoapSession) s).getPreviousSessionTime(), Element.Disposition.CONTENT);
                    response.addAttribute(AccountConstants.E_LAST_ACCESS, ((SoapSession) s).getLastWriteAccessTime(), Element.Disposition.CONTENT);
                    response.addAttribute(AccountConstants.E_RECENT_MSGS, ((SoapSession) s).getRecentMessageCount(), Element.Disposition.CONTENT);
                } else {
                    // we have no session; calculate the stats from the mailbox and the other SOAP sessions
                    long lastAccess = mbox.getLastSoapAccessTime();
                    response.addAttribute(AccountConstants.E_PREVIOUS_SESSION, lastAccess, Element.Disposition.CONTENT);
                    response.addAttribute(AccountConstants.E_LAST_ACCESS, lastAccess, Element.Disposition.CONTENT);
                    response.addAttribute(AccountConstants.E_RECENT_MSGS, mbox.getRecentMessageCount(), Element.Disposition.CONTENT);
                }
            } catch (ServiceException e) { }
        }
        
        doCos(account, response);

        Map<String, Object> attrMap = account.getUnicodeAttrs();
        Locale locale = Provisioning.getInstance().getLocale(account);

        if (sections.contains(Section.PREFS)) {
            Element prefs = response.addUniqueElement(AccountConstants.E_PREFS);
            GetPrefs.doPrefs(account, prefs, attrMap, null);
        }
        if (sections.contains(Section.ATTRS)) {
            Element attrs = response.addUniqueElement(AccountConstants.E_ATTRS);
            doAttrs(account, locale.toString(), attrs, attrMap);
        }
        if (sections.contains(Section.ZIMLETS)) {
            Element zimlets = response.addUniqueElement(AccountConstants.E_ZIMLETS);
            doZimlets(zimlets, account);
        }
        if (sections.contains(Section.PROPS)) {
            Element props = response.addUniqueElement(AccountConstants.E_PROPERTIES);
            doProperties(props, account);
        }
        if (sections.contains(Section.IDENTS)) {
            Element ids = response.addUniqueElement(AccountConstants.E_IDENTITIES);
            doIdentities(ids, account);
        }
        if (sections.contains(Section.SIGS)) {
            Element sigs = response.addUniqueElement(AccountConstants.E_SIGNATURES);
            doSignatures(sigs, account);
        }
        if (sections.contains(Section.DSRCS)) {
            Element ds = response.addUniqueElement(AccountConstants.E_DATA_SOURCES);
            doDataSources(ds, account, zsc);
        }
        if (sections.contains(Section.CHILDREN)) {
            Element ca = response.addUniqueElement(AccountConstants.E_CHILD_ACCOUNTS);
            doChildAccounts(ca, account, zsc.getAuthToken());
        }
        
        GetAccountInfo.addUrls(response, account);
        return response;
    }
    
    static void doCos(Account acct, Element response) throws ServiceException {
        Cos cos = Provisioning.getInstance().getCOS(acct);
        if (cos != null) {
            Element eCos = response.addUniqueElement(AccountConstants.E_COS);
            eCos.addAttribute(AccountConstants.A_ID, cos.getId());
            eCos.addAttribute(AccountConstants.A_NAME, cos.getName());
        }
    }

    static void doAttrs(Account acct, String locale, Element response, Map<String,Object> attrsMap) throws ServiceException {
        AttributeManager attrMgr = AttributeManager.getInstance();
        
        Set<String> attrList = attrMgr.getAttrsWithFlag(AttributeFlag.accountInfo);
        
        Set<String> acctAttrs = attrMgr.getAllAttrsInClass(AttributeClass.account);
        Set<String> domainAttrs = attrMgr.getAllAttrsInClass(AttributeClass.domain);
        Set<String> serverAttrs = attrMgr.getAllAttrsInClass(AttributeClass.server);
        Set<String> configAttrs = attrMgr.getAllAttrsInClass(AttributeClass.globalConfig);
        
        Provisioning prov = Provisioning.getInstance();
        Domain domain = prov.getDomain(acct);
        Server server = acct.getServer();
        Config config = prov.getConfig();
        
        for (String key : attrList) {
            Object value = null;
            if (Provisioning.A_zimbraLocale.equals(key)) {
                value = locale;
            } else if (Provisioning.A_zimbraAttachmentsBlocked.equals(key)) {
                // leave this a special case for now, until we have enough incidences to make it a pattern
                value = config.isAttachmentsBlocked() || acct.isAttachmentsBlocked() ? Provisioning.TRUE : Provisioning.FALSE;
            } else {
                value = attrsMap.get(key);
                
                if (value == null) { // no value on account/cos
                    if (!acctAttrs.contains(key)) { // not an account attr
                        // see if it is on domain, server, or globalconfig
                        if (domainAttrs.contains(key)) {
                            if (domain != null) {
                                value = domain.getMultiAttr(key); // value on domain/global config (domainInherited)
                            }
                        } else if (serverAttrs.contains(key)) {
                            value = server.getMultiAttr(key); // value on server/global config (serverInherited)
                        } else if (configAttrs.contains(key)) {
                            value = config.getMultiAttr(key); // value on global config
                        }
                    }
                }
            }

            doAttr(response, key, value);
        }
    }
    
    static void doAttr(Element response, String key, Object value) {
        if (value instanceof String[]) {
            String sa[] = (String[]) value;
            for (int i = 0; i < sa.length; i++) {
                // FIXME: change to "a"/"n" rather than "attr"/"name"
                if (sa[i] != null && !sa[i].equals(""))
                    response.addKeyValuePair(key, sa[i], AccountConstants.E_ATTR, AccountConstants.A_NAME);
            }
        } else {
            if (value != null && !value.equals(""))
                response.addKeyValuePair(key, (String) value, AccountConstants.E_ATTR, AccountConstants.A_NAME);
        }        
    }
    
    
    private static void doZimlets(Element response, Account acct) {
        try {
            // bug 34517
            ZimletUtil.migrateUserPrefIfNecessary(acct);
            
            ZimletPresence userZimlets = ZimletUtil.getUserZimlets(acct);
            List<Zimlet> zimletList = ZimletUtil.orderZimletsByPriority(userZimlets.getZimletNamesAsArray());
            int priority = 0;
            for (Zimlet z : zimletList) {
                if (z.isEnabled() && !z.isExtension())
                    ZimletUtil.listZimlet(response, z, priority, userZimlets.getPresence(z.getName()));
                priority++;
            }
    
            // load the zimlets in the dev directory and list them
            ZimletUtil.listDevZimlets(response);
        } catch (ServiceException se) {
            ZimbraLog.account.error("can't get zimlets", se);
        }
    }

    private static void doProperties(Element response, Account acct) {
        ZimletUserProperties zp = ZimletUserProperties.getProperties(acct);
        Set<? extends Prop> props = zp.getAllProperties();
        for (Prop prop : props) {
            Element elem = response.addElement(AccountConstants.E_PROPERTY);
            elem.addAttribute(AccountConstants.A_ZIMLET, prop.getZimlet());
            elem.addAttribute(AccountConstants.A_NAME, prop.getName());
            elem.setText(prop.getValue());
        }
    }
    
    private static void doIdentities(Element response, Account acct) {
        try {
            List<Identity> identities = Provisioning.getInstance().getAllIdentities(acct);
            for (Identity i : identities)
                ToXML.encodeIdentity(response, i);
        } catch (ServiceException se) {
            ZimbraLog.account.error("can't get identities", se);
        }
    }
    
    private static void doSignatures(Element response, Account acct) {
        try {
            List<Signature> signatures = Provisioning.getInstance().getAllSignatures(acct);
            for (Signature s : signatures)
                ToXML.encodeSignature(response, s);
        } catch (ServiceException se) {
            ZimbraLog.account.error("can't get signatures", se);
        }
    }
    
    private static void doDataSources(Element response, Account acct, ZimbraSoapContext zsc) {
        try {
            List<DataSource> dataSources = Provisioning.getInstance().getAllDataSources(acct);
            for (DataSource ds : dataSources)
                if (!ds.isInternal())
                    com.zimbra.cs.service.mail.ToXML.encodeDataSource(response, ds);
        } catch (ServiceException se) {
            ZimbraLog.mailbox.error("Unable to get data sources", se);
        }
    }
 
    protected void doChildAccounts(Element response, Account acct, AuthToken authToken) throws ServiceException {
        String[] childAccounts = acct.getMultiAttr(Provisioning.A_zimbraChildAccount);
        String[] visibleChildAccounts = acct.getMultiAttr(Provisioning.A_zimbraPrefChildVisibleAccount);

        if (childAccounts.length == 0 && visibleChildAccounts.length == 0)
            return;

        Provisioning prov = Provisioning.getInstance();
        Set<String> children = new HashSet<String>(childAccounts.length);

        for (String childId : visibleChildAccounts) {
            if (children.contains(childId))
                continue;
            Account child = prov.get(Provisioning.AccountBy.id, childId, authToken);
            if (child != null)
                encodeChildAccount(response, child, true);
            children.add(childId);
        }

        for (String childId : childAccounts) {
            if (children.contains(childId))
                continue;
            Account child = prov.get(Provisioning.AccountBy.id, childId, authToken);
            if (child != null)
                encodeChildAccount(response, child, false);
            children.add(childId);
        }
    }

    protected Element encodeChildAccount(Element parent, Account child, boolean isVisible) {
        Element elem = parent.addElement(AccountConstants.E_CHILD_ACCOUNT);
        elem.addAttribute(AccountConstants.A_ID, child.getId());
        elem.addAttribute(AccountConstants.A_NAME, child.getUnicodeName());
        elem.addAttribute(AccountConstants.A_VISIBLE, isVisible);
        elem.addAttribute(AccountConstants.A_ACTIVE, child.isAccountStatusActive());

        String displayName = child.getAttr(Provisioning.A_displayName);
        if (displayName != null) {
            Element attrsElem = elem.addUniqueElement(AccountConstants.E_ATTRS);
            attrsElem.addKeyValuePair(Provisioning.A_displayName, displayName, AccountConstants.E_ATTR, AccountConstants.A_NAME);
        }
        return elem;
    }
}
