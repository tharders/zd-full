/*
 * 
 */

package com.zimbra.soap.account.type;

import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlEnum;
import javax.xml.bind.annotation.XmlEnumValue;
import javax.xml.bind.annotation.XmlValue;

public class Account {

    @XmlEnum
    public enum By {
        @XmlEnumValue("name") NAME,
        @XmlEnumValue("id") ID,
        @XmlEnumValue("foreignPrincipal") FOREIGN_PRINCIPAL,
        @XmlEnumValue("adminName") ADMIN_NAME,
        @XmlEnumValue("appAdminName") APP_ADMIN_NAME,
        @XmlEnumValue("krb5Principal") KRB5_PRINCIPAL
    }
    
    @XmlValue private String key;
    @XmlAttribute(name="by") private By by;

    public Account() {
    }
    
    public Account(By by, String key) {
        setBy(by);
        setKey(key);
    }
    
    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    
    public By getBy() { return by; }
    public void setBy(By by) { this.by = by; }
}
