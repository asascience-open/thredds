/*
 * Copyright 1998-2009 University Corporation for Atmospheric Research/Unidata
 *
 * Portions of this software were developed by the Unidata Program at the
 * University Corporation for Atmospheric Research.
 *
 * Access and use of this software shall impose the following obligations
 * and understandings on the user. The user is granted the right, without
 * any fee or cost, to use, copy, modify, alter, enhance and distribute
 * this software, and any derivative works thereof, and its supporting
 * documentation for any purpose whatsoever, provided that this entire
 * notice appears in all copies of the software, derivative works and
 * supporting documentation.  Further, UCAR requests that the user credit
 * UCAR/Unidata in any publications that result from the use of this
 * software or in any product that includes this software. The names UCAR
 * and/or Unidata, however, may not be used in any advertising or publicity
 * to endorse or promote any products or commercial entity unless specific
 * written permission is obtained from UCAR/Unidata. The user also
 * understands that UCAR/Unidata is not obligated to provide the user with
 * any support, consulting, training or assistance of any kind with regard
 * to the use, operation and performance of this software nor to provide
 * the user with any updates, revisions, new versions or "bug fixes."
 *
 * THIS SOFTWARE IS PROVIDED BY UCAR/UNIDATA "AS IS" AND ANY EXPRESS OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL UCAR/UNIDATA BE LIABLE FOR ANY SPECIAL,
 * INDIRECT OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES WHATSOEVER RESULTING
 * FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN ACTION OF CONTRACT,
 * NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF OR IN CONNECTION
 * WITH THE ACCESS, USE OR PERFORMANCE OF THIS SOFTWARE.
 */
package thredds.server.catalogservice;

import org.springframework.web.servlet.mvc.AbstractController;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.util.HtmlUtils;
import org.springframework.validation.BindingResult;
import org.springframework.validation.ObjectError;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import thredds.servlet.HtmlWriter;
import thredds.servlet.ServletUtil;
import thredds.servlet.ThreddsConfig;
import thredds.server.config.TdsContext;
import thredds.server.views.InvCatalogXmlView;
import thredds.catalog.*;

import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.net.URI;

/**
 * Handle all requests for catalog services on remote catalogs. Supported
 * services are:
 * <ol>
 * <li>catalog validation,</li>
 * <li>catalog subsetting, and</li>
 * <li>HTML views of catalogs (full or subset) and datasets.</li>
 *
 *
 * Currently, handles the following TDS requests:
 * <ul>
 *   <li>Mapping="/remoteCatalogService"</li>
 * </ul>
 *
 * <p> NOTE: Only supported if CatalogServices/allowRemote is set to "true" in threddsConfig.xml.
 *
 * <p> Uses the following information from an HTTP request:
 * <ul>
 *   <li>The "catalog" parameter gives the URI of a remote catalog.</li>
 *   <li>The "command" parameter must either be empty or have one of the
 *     following values: "SHOW", "SUBSET", or "VALIDATE", see
 *     {@link Command}.</li>
 *   <li>The "dataset" parameter identifies a dataset contained by the
 *     local catalog. [Used only in "SUBSET" requests.]</li>
 *   <li>The "htmlView" parameter indicates if an HTML or XML view is
 *     desired. [Used only in "SUBSET" requests.]</li>
 *   <li>The "verbose" parameter indicates if the output of a "VALIDATE"
 *     request should be verbose ("true") or not ("false" or not given).</li>
 * </ul>
 *
 * <p>Constraints on the above information:
 * <ul>
 *   <li>The catalog URI must be absolute and is expected to reference a
 *     THREDDS catalog XML document.</li>
 *   <li>The "dataset" parameter must either be empty or contain the value
 *     of a dataset ID contained in the catalog.</li>
 *   <li>If the "command" parameter is empty, it will default to "SHOW" if
 *     the "dataset" parameter is empty, otherwise it will default to "SUBSET".</li>
 * </ul>
 *
 * <p>The above information is contained in a {@link RemoteCatalogRequest} command
 * object while the constraints are enforced by {@link RemoteCatalogRequestDataBinder}
 * and {@link RemoteCatalogRequestValidator}.
 *
 * @author edavis
 * @since 4.0
 * @see thredds.util.TdsPathUtils#extractPath(javax.servlet.http.HttpServletRequest)
 * @see Command
 * @see RemoteCatalogRequest
 * @see RemoteCatalogRequestDataBinder
 * @see RemoteCatalogRequestValidator
 */
public class RemoteCatalogServiceController extends AbstractController
{
  private org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger( getClass() );

  private TdsContext tdsContext;

  public void setTdsContext( TdsContext tdsContext )
  {
    this.tdsContext = tdsContext;
  }

  protected ModelAndView handleRequestInternal( HttpServletRequest request,
                                                HttpServletResponse response )
          throws Exception
  {
    // Gather diagnostics for logging request.
    ServletUtil.logServerAccessSetup( request );

    // Send error response if remote catalog service requests are not allowed.
    // ToDo Look - Move this into TdsConfig?
    boolean allowRemote = ThreddsConfig.getBoolean( "CatalogServices.allowRemote", false );
    if ( ! allowRemote )
    {
      ServletUtil.logServerAccess( HttpServletResponse.SC_FORBIDDEN, -1 );
      response.sendError( HttpServletResponse.SC_FORBIDDEN, "Catalog services not supported for remote catalogs." );
      return null;
    }
    // Bind HTTP request to a LocalCatalogRequest.
    BindingResult bindingResult = CatalogServiceUtils.bindAndValidateRemoteCatalogRequest( request );

    // If any binding or validation errors, return BAD_REQUEST.
    if ( bindingResult.hasErrors() )
    {
      StringBuilder msg = new StringBuilder( "Bad request" );
      List<ObjectError> oeList = bindingResult.getAllErrors();
      for ( ObjectError e : oeList )
        msg.append( ": " ).append( e.toString() );
      log.error( "handleRequestInternal(): " + msg );
      ServletUtil.logServerAccess( HttpServletResponse.SC_BAD_REQUEST, msg.length() );
      response.sendError( HttpServletResponse.SC_BAD_REQUEST, msg.toString() );
      return null;
    }

    // Retrieve the resulting RemoteCatalogRequest.
    RemoteCatalogRequest catalogServiceRequest = (RemoteCatalogRequest) bindingResult.getTarget();

    // Determine path and catalogPath
    URI uri = catalogServiceRequest.getCatalogUri();

    // Check for matching catalog.
    InvCatalogImpl catalog = null;
    InvCatalogFactory fac = InvCatalogFactory.getDefaultFactory( true );
    try
    {
      catalog = fac.readXML( uri );
    }
    catch ( Throwable t )
    {
      String msg = "Error reading catalog [" + uri + "]: " + t.getMessage();
      log.error( "handleRequestInternal(): " + msg );
      ServletUtil.logServerAccess( HttpServletResponse.SC_BAD_REQUEST, msg.length() );
      response.sendError( HttpServletResponse.SC_BAD_REQUEST, msg.toString() );
      return null;
    }

    // Check whether a catalog was found.
    if ( catalog == null )
    {
      String msg = "Failed to read catalog [" + uri + "].";
      log.error( "handleRequestInternal(): " + msg );
      ServletUtil.logServerAccess( HttpServletResponse.SC_BAD_REQUEST, msg.length() );
      response.sendError( HttpServletResponse.SC_BAD_REQUEST, msg.toString() );
      return null;
    }

    // Check catalog validity.
    StringBuilder validateMess = new StringBuilder();
    boolean verbose = catalogServiceRequest.isVerbose();
    catalog.check( validateMess, verbose );
    if ( catalog.hasFatalError() )
    {
      Map<String, Object> model = new HashMap<String,Object>();
      model.put( "catalogUrl", uri );
      model.put( "message", validateMess.toString() );
      model.put( "siteLogoPath", HtmlWriter.getInstance().getContextPath() + HtmlWriter.getInstance().getContextLogoPath() );
      model.put( "siteLogoAlt", HtmlWriter.getInstance().getContextLogoAlt() );
      model.put( "serverName", HtmlWriter.getInstance().getContextName() );
      ServletUtil.logServerAccess( HttpServletResponse.SC_OK, -1 );
      return new ModelAndView( "/thredds/server/catalogservice/validationError", model );
    }

    ///////////////////////////////////////////
    // Otherwise, handle catalog as indicated by "command".
    if ( catalogServiceRequest.getCommand().equals( Command.SHOW))
    {
      HtmlWriter.getInstance().writeCatalog( response, (InvCatalogImpl) catalog, true );
      return null;
    }
    else if ( catalogServiceRequest.getCommand().equals( Command.SUBSET ))
    {
      String datasetId = catalogServiceRequest.getDataset();
      InvDataset dataset = catalog.findDatasetByID( datasetId );
      if ( dataset == null )
      {
        String msg = "Did not find dataset [" + datasetId + "] in catalog [" + uri + "].";
        log.error( "handleRequestInternal(): " + msg );
        ServletUtil.logServerAccess( HttpServletResponse.SC_BAD_REQUEST, msg.length() );
        response.sendError( HttpServletResponse.SC_BAD_REQUEST, msg.toString() );
        return null;
      }

      if ( catalogServiceRequest.isHtmlView() )
      {
        HtmlWriter.getInstance().showDataset( uri.toString(), (InvDatasetImpl) dataset, request, response );
        return null;
      }
      else
      {
        catalog.subset( dataset ); // subset the catalog
        return new ModelAndView( new InvCatalogXmlView(), "catalog", catalog );
      }
    }
    else if ( catalogServiceRequest.getCommand().equals( Command.VALIDATE ) )
    {
      Map<String, Object> model = new HashMap<String, Object>();
      model.put( "catalogUrl", uri );
      model.put( "message", validateMess.toString() );
      model.put( "siteLogoPath", HtmlWriter.getInstance().getContextPath() + HtmlWriter.getInstance().getContextLogoPath() );
      model.put( "siteLogoAlt", HtmlWriter.getInstance().getContextLogoAlt() );
      model.put( "serverName", HtmlWriter.getInstance().getContextName() );
      ServletUtil.logServerAccess( HttpServletResponse.SC_OK, -1 );
      return new ModelAndView( "/thredds/server/catalogservice/validationMessage", model );
    }
    else
    {
      String msg = "Unsupported request command [" + catalogServiceRequest.getCommand() + "].";
      log.error( "handleRequestInternal(): " + msg + " -- NOTE: Should have been caught on input validation." );
      ServletUtil.logServerAccess( HttpServletResponse.SC_BAD_REQUEST, msg.length() );
      response.sendError( HttpServletResponse.SC_BAD_REQUEST, msg.toString() );
      return null;
    }
  }

  private ModelAndView constructModelForCatalogView( InvCatalog cat )
  {
    // Hand to catalog view.
    String catName = cat.getName();
    String catUri = cat.getUriString();
    if ( catName == null )
    {
      List childrenDs = cat.getDatasets();
      if ( childrenDs.size() == 1 )
      {
        InvDatasetImpl onlyChild = (InvDatasetImpl) childrenDs.get( 0 );
        catName = onlyChild.getName();
      }
      else
        catName = "";
    }

    Map<String, Object> model = new HashMap<String, Object>();
    model.put( "catalog", cat );
    model.put( "catalogName", HtmlUtils.htmlEscape( catName ) );
    model.put( "catalogUri", HtmlUtils.htmlEscape( catUri ) );
    model.put( "webappName", this.getServletContext().getServletContextName() );
    model.put( "webappVersion", tdsContext.getWebappVersion() );
    model.put( "webappBuildDate", tdsContext.getWebappBuildDate() );
    model.put( "webappDocsPath", tdsContext.getTdsConfigHtml().getWebappDocsPath() );
    return new ModelAndView( "thredds/server/catalog/catalog", model );
  }
}