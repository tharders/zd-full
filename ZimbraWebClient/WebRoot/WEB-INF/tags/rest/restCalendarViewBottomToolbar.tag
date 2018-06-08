<%--
 * 
--%>
<%@ tag body-content="empty" %>
<%@ attribute name="timezone" rtexprvalue="true" required="true" type="java.util.TimeZone"%>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%@ taglib prefix="fn" uri="http://java.sun.com/jsp/jstl/functions" %>
<%@ taglib prefix="fmt" uri="com.zimbra.i18n" %>
<%@ taglib prefix="app" uri="com.zimbra.htmlclient" %>
<%@ taglib prefix="zm" uri="com.zimbra.zm" %>

<fmt:setBundle basename='/messages/AjxMsg' var='AjxMsg' scope='request' />
<fmt:message bundle='${AjxMsg}' key='${zm:getCanonicalId(timezone)}' var='timezoneStr' scope='request' />

<%-- TODO: blank for now, could add timezone drop down or more date selection --%>
<table width="100%" cellspacing="0" class='Tb'>
    <tr>
        <td align="left" class="TbBt">
            <c:if test="${not empty requestScope.zimbra_target_item_name}">
                <a href="/home/${requestScope.zimbra_target_account_name}${requestScope.zimbra_target_item_path}.ics"><app:img src="startup/ImgCalendarApp.png" alt="ics"/><span style='padding-left:5px'>${zm:cook(requestScope.zimbra_target_item_name)}.ics</span></a>
            </c:if>
        </td>
        <td align='right' class='ZhCalTimeZone'>
            ${fn:escapeXml(fn:startsWith(timezoneStr,"???") ? (zm:getCanonicalId(timezone)) : timezoneStr)}
        </td>
    </tr>
</table>