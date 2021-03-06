<%--
 * 
--%>
<%@ tag body-content="empty" %>
<%@ attribute name="message" rtexprvalue="true" required="true" type="com.zimbra.cs.taglib.bean.ZMessageBean" %>

<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%@ taglib prefix="fn" uri="http://java.sun.com/jsp/jstl/functions" %>
<%@ taglib prefix="fmt" uri="com.zimbra.i18n" %>
<%@ taglib prefix="zm" uri="com.zimbra.zm" %>
<%@ taglib prefix="app" uri="com.zimbra.htmlclient" %>

<c:forEach var="part" items="${message.attachments}">
    <c:if test="${part.isImage}">
       <hr/>
       <c:set var="url" value="/service/home/~/?id=${message.id}&amp;part=${part.partName}&amp;auth=co"/>
       <span class="ShowAllImageName">${fn:escapeXml(part.displayName)}</span>
       <br/><br/><img class="ShowAllImageItem" src="${url}" alt="${fn:escapeXml(part.displayName)}" border="0"/><br/><br/>
    </c:if>
</c:forEach>