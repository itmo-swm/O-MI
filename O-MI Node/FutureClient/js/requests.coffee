# import WebOmi, add submodule
requestsExt = (WebOmi) ->
  # Sub module for containing all request type templates 
  my = WebOmi.requests = {}

  my.xmls =
    readAll :
      """
      <?xml version="1.0"?>
      <omi:omiEnvelope xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:omi="omi.xsd"
          version="1.0" ttl="0">
        <omi:read msgformat="odf">
          <omi:msg xmlns="odf.xsd" xsi:schemaLocation="odf.xsd odf.xsd">
            <Objects></Objects>
          </omi:msg>
        </omi:read>
      </omi:omiEnvelope> 
      """
    template :
      """
      <?xml version="1.0"?>
      <omi:omiEnvelope xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:omi="omi.xsd"
          version="1.0" ttl="0">
        <omi:read msgformat="odf">
          <omi:msg xmlns="odf.xsd" xsi:schemaLocation="odf.xsd odf.xsd">
          </omi:msg>
        </omi:read>
      </omi:omiEnvelope> 

      """

  my.defaults =
    ttl: 0
    callback: ""
    requestID: 1


  # @param fastforward: Boolean Whether to also send the request and update odfTree also
  my.readAll = (fastForward) ->
    WebOmi.formLogic.setRequest my.xmls.readAll
    if fastForward
      WebOmi.formLogic.send(WebOmi.formLogic.buildOdfTreeStr)


  my.addPathToOdf = (path) ->
    reqCM = WebOmi.consts.requestCodeMirror
    reqCM.getValue()
    # TODO:

  my.read = () ->

          

  WebOmi # export module

# extend WebOmi
window.WebOmi = requestsExt(window.WebOmi || {})
