// Generated by CoffeeScript 1.10.0
(function() {
  var omiExt;

  omiExt = function(WebOmi) {
    var createOdf, my;
    my = WebOmi.omi = {};
    my.parseXml = function(responseString) {
      var error, ex, xmlTree;
      if (responseString < 2) {
        return null;
      }
      try {
        xmlTree = new DOMParser().parseFromString(responseString, 'application/xml');
      } catch (error) {
        ex = error;
        xmlTree = null;
        WebOmi.debug("DOMParser xml parsererror or not supported!");
      }
      if (xmlTree.firstElementChild.nodeName === "parsererror" || (xmlTree == null)) {
        WebOmi.debug("PARSE ERROR:");
        WebOmi.debug("in:", responseString);
        WebOmi.debug("out:", xmlTree);
        xmlTree = null;
      }
      return xmlTree;
    };
    my.ns = {
      omi: "omi.xsd",
      odf: "odf.xsd",
      xsi: "http://www.w3.org/2001/XMLSchema-instance",
      xs: "http://www.w3.org/2001/XMLSchema-instance"
    };
    my.nsResolver = function(name) {
      return my.ns[name] || my.ns.odf;
    };
    my.evaluateXPath = function(elem, xpath) {
      var iter, res, results, xpe;
      xpe = elem.ownerDocument || elem;
      iter = xpe.evaluate(xpath, elem, my.nsResolver, 0, null);
      results = [];
      while (res = iter.iterateNext()) {
        results.push(res);
      }
      return results;
    };
    createOdf = function(elem, doc) {
      return doc.createElementNS(my.ns.odf, elem);
    };
    my.createOmi = function(elem, doc) {
      return doc.createElementNS(my.ns.omi, elem);
    };
    my.createOdfValue = function(doc, value, valueType, valueTime) {
      var odfVal;
      if (value == null) {
        value = null;
      }
      if (valueType == null) {
        valueType = null;
      }
      if (valueTime == null) {
        valueTime = null;
      }
      odfVal = createOdf("value", doc);
      if (value != null) {
        odfVal.appendChild(doc.createTextNode(value));
      }
      if (valueType != null) {
        odfVal.setAttribute("type", "xs:" + valueType);
      }
      if (valueTime != null) {
        odfVal.setAttribute("unixTime", valueTime);
      }
      return odfVal;
    };
    my.createOdfMetaData = function(doc) {
      return createOdf("MetaData", doc);
    };
    my.createOdfDescription = function(doc, text) {
      var descElem, textElem;
      descElem = createOdf("description", doc);
      if (text != null) {
        textElem = doc.createTextNode(text);
        descElem.appendChild(textElem);
      }
      return descElem;
    };
    my.createOdfObjects = function(doc) {
      return createOdf("Objects", doc);
    };
    my.createOdfObject = function(doc, id) {
      var createdElem, idElem, textElem;
      createdElem = createOdf("Object", doc);
      idElem = createOdf("id", doc);
      textElem = doc.createTextNode(id);
      idElem.appendChild(textElem);
      createdElem.appendChild(idElem);
      return createdElem;
    };
    my.createOdfInfoItem = function(doc, name, values, description) {
      var createdElem, i, len, val, value;
      if (values == null) {
        values = [];
      }
      if (description == null) {
        description = null;
      }
      createdElem = createOdf("InfoItem", doc);
      createdElem.setAttribute("name", name);
      for (i = 0, len = values.length; i < len; i++) {
        value = values[i];
        val = my.createOdfValue(doc, value.value, value.type, value.time);
        createdElem.appendChild(val);
      }
      if (description != null) {
        createdElem.insertBefore(my.createOdfDescription(doc, description), createdElem.firstChild);
      }
      return createdElem;
    };
    my.getOdfId = function(xmlNode) {
      var head, nameAttr;
      switch (xmlNode.nodeName) {
        case "Object":
          head = my.evaluateXPath(xmlNode, './odf:id')[0];
          if (head != null) {
            return head.textContent.trim();
          } else {
            return null;
          }
          break;
        case "InfoItem":
          nameAttr = xmlNode.attributes.name;
          if (nameAttr != null) {
            return nameAttr.value;
          } else {
            return null;
          }
          break;
        case "Objects":
          return "Objects";
        case "MetaData":
          return "MetaData";
        case "description":
          return "description";
        default:
          return null;
      }
    };
    my.getOdfChild = function(odfId, odfNode) {
      var child, i, len, ref;
      ref = odfNode.childNodes;
      for (i = 0, len = ref.length; i < len; i++) {
        child = ref[i];
        if (my.getOdfId(child) === odfId) {
          return child;
        }
      }
      return null;
    };
    my.hasOdfChildren = function(odfNode) {
      var child, i, len, maybeId, ref;
      ref = odfNode.childNodes;
      for (i = 0, len = ref.length; i < len; i++) {
        child = ref[i];
        maybeId = my.getOdfId(child);
        if ((maybeId != null) && maybeId !== "") {
          return true;
        }
      }
      return false;
    };
    return WebOmi;
  };

  window.WebOmi = omiExt(window.WebOmi || {});

  window.omi = "ready";

}).call(this);
