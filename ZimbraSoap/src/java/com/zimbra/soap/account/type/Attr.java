/*
 * 
 */

package com.zimbra.soap.account.type;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlValue;

import com.google.common.base.Function;
import com.google.common.base.Objects;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;


/*
<attr name="{name}">{value}</attr>
 */
public class Attr {

    public static Function<Attr, Attr> COPY = new Function<Attr, Attr>() {
        @Override
        public Attr apply(Attr from) {
            return new Attr(from);
        }
    };

    @XmlAttribute private String name;
    @XmlValue private String value;

    public Attr() {
    }

    public Attr(Attr attr) {
        name = attr.getName();
        value = attr.getValue();
    }

    public Attr(String name) {
        setName(name);
    }

    public Attr(String name, String value) {
        setName(name);
        setValue(value);
    }

    public String getName() { return name; }
    public Attr setName(String name) { this.name = name; return this; }

    public String getValue() { return value; }
    public Attr setValue(String value) { this.value = value; return this; }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
            .add("name", name)
            .add("value", value)
            .toString();
    }

    public static Multimap<String, String> toMultimap(List<Attr> attrs) {
        Multimap<String, String> map = ArrayListMultimap.create();
        if (attrs != null) {
            for (Attr a : attrs) {
                map.put(a.getName(), a.getValue());
            }
        }
        return map;
    }

    public static List<Attr> fromMultimap(Multimap<String, String> attrMap) {
        List<Attr> attrs = new ArrayList<Attr>();
        if (attrMap != null) {
            for (Map.Entry<String, String> entry : attrMap.entries()) {
                attrs.add(new Attr(entry.getKey(), entry.getValue()));
            }
        }
        return attrs;
    }
}
