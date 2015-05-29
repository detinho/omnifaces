/*
 * Copyright 2012 OmniFaces.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package org.omnifaces.facesviews;

import static javax.faces.application.ProjectStage.Development;
import static javax.servlet.http.HttpServletResponse.SC_MOVED_PERMANENTLY;
import static javax.servlet.http.HttpServletResponse.SC_NOT_FOUND;
import static org.omnifaces.facesviews.FacesViews.FACES_VIEWS_ORIGINAL_SERVLET_PATH;
import static org.omnifaces.facesviews.FacesViews.FACES_VIEWS_RESOURCES;
import static org.omnifaces.facesviews.FacesViews.FACES_VIEWS_REVERSE_RESOURCES;
import static org.omnifaces.facesviews.FacesViews.getExtensionAction;
import static org.omnifaces.facesviews.FacesViews.getExtensionlessURLWithQuery;
import static org.omnifaces.facesviews.FacesViews.getFacesServletDispatchMethod;
import static org.omnifaces.facesviews.FacesViews.getPathAction;
import static org.omnifaces.facesviews.FacesViews.isResourceInPublicPath;
import static org.omnifaces.facesviews.FacesViews.scanAndStoreViews;
import static org.omnifaces.util.Faces.getApplicationFromFactory;
import static org.omnifaces.util.ResourcePaths.getExtension;
import static org.omnifaces.util.ResourcePaths.isExtensionless;
import static org.omnifaces.util.Servlets.getApplicationAttribute;

import java.io.IOException;
import java.util.Map;

import javax.servlet.FilterChain;
import javax.servlet.RequestDispatcher;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.omnifaces.filter.HttpFilter;

/**
 * This filter makes sure extensionless requests arrive at the FacesServlet using an extension on which that Servlet is mapped,
 * and that non-extensionless requests are handled according to a set preference.
 * <p>
 * For dispatching to the FacesServlet, 2 methods are available:
 *
 * <ul>
 * <li> DO_FILTER (continues the filter chain but modifies request) </li>
 * <li> FORWARD (starts a new filter chain by using a Servlet requestDispatcher.forward) </li>
 * </ul>
 *
 * <p>
 * A filter like this is needed for extensionless requests, since the FacesServlet in at least JSF 2.1 and before
 * does not take into account any other mapping than prefix- and extension (suffix) mapping.
 * <p>
 * For a guide on FacesViews, please see the <a href="package-summary.html">package summary</a>.
 *
 * @author Arjan Tijms
 *
 */
public class FacesViewsForwardingFilter extends HttpFilter {

	private ExtensionAction extensionAction;
	private PathAction pathAction;
	private FacesServletDispatchMethod dispatchMethod;

	@Override
	public void init() throws ServletException {
		ServletContext servletContext = getServletContext();

		try {
			extensionAction = getExtensionAction(servletContext);
			pathAction = getPathAction(servletContext);
			dispatchMethod = getFacesServletDispatchMethod(servletContext);
		}
		catch (IllegalStateException e) {
			throw new ServletException(e);
		}
	}

	@Override
	public void doFilter(HttpServletRequest request, HttpServletResponse response, HttpSession session, FilterChain chain) throws ServletException, IOException {
		String resource = request.getServletPath();

		if (filterExtensionLess(request, response, chain, resource)) {
			return;
		}
		else if (filterExtension(request, response, resource)) {
			return;
		}
		else if (filterPublicPath(request, response, resource)) {
			return;
		}

		chain.doFilter(request, response);
	}

	/**
	 * A mapped resource request without extension is encountered.
	 * The user setting "dispatchMethod" determines how we handle this.
	 */
	private boolean filterExtensionLess(HttpServletRequest request, HttpServletResponse response, FilterChain chain, String resource) throws IOException, ServletException {
		if (!isExtensionless(resource)) {
			return false;
		}

		Map<String, String> resources = getApplicationAttribute(getServletContext(), FACES_VIEWS_RESOURCES);

		if (getApplicationFromFactory().getProjectStage() == Development && !resources.containsKey(resource)) {
			// Check if the resource was dynamically added by scanning the faces-views location(s) again.
			resources = scanAndStoreViews(getServletContext());
		}

		if (resources.containsKey(resource)) {
			String extension = getExtension(resources.get(resource));

			switch (dispatchMethod) {
				case DO_FILTER:
					// Continue the chain, but make the request appear to be to the resource with an extension.
					// This assumes that the FacesServlet has been mapped to something that includes the extensionless
					// request.
					try {
						request.setAttribute(FACES_VIEWS_ORIGINAL_SERVLET_PATH, request.getServletPath());
						chain.doFilter(new UriExtensionRequestWrapper(request, extension), response);
					}
					finally {
						request.removeAttribute(FACES_VIEWS_ORIGINAL_SERVLET_PATH);
					}

					return true;
				case FORWARD:
					// Forward the resource (view) using its original extension, on which the Facelets Servlet
					// is mapped. Technically it matters most that the Facelets Servlet picks up the
					// request, and the exact extension or even prefix is perhaps less relevant.
					RequestDispatcher requestDispatcher = getServletContext().getRequestDispatcher(resource + extension);

					if (requestDispatcher != null) {
						requestDispatcher.forward(request, response);
						return true;
					}
			}
		}

		return false;
	}

	/**
	 * A mapped resource request with extension is encountered.
	 * The user setting "extensionAction" determines how we handle this.
	 */
	private boolean filterExtension(HttpServletRequest request, HttpServletResponse response, String resource) throws IOException {
		Map<String, String> resources = getApplicationAttribute(getServletContext(), FACES_VIEWS_RESOURCES);

		if (resources.containsKey(resource)) {
			switch (extensionAction) {
				case REDIRECT_TO_EXTENSIONLESS:
					redirectPermanent(response, getExtensionlessURLWithQuery(request));
					return true;
				case SEND_404:
					response.sendError(SC_NOT_FOUND);
					return true;
				case PROCEED:
					break;
				default:
					break;
			}
		}

		return false;
	}

	/**
	 * A direct request to one of the public paths (excluding /) from where we scanned resources is encountered.
	 * The user setting "pathAction" determines how we handle this.
	 */
	private boolean filterPublicPath(HttpServletRequest request, HttpServletResponse response, String resource) throws IOException {
		if (!isResourceInPublicPath(getServletContext(), resource)) {
			return false;
		}

		Map<String, String> reverseResources = getApplicationAttribute(getServletContext(), FACES_VIEWS_REVERSE_RESOURCES);

		if (reverseResources.containsKey(resource)) {
			switch (pathAction) {
				case REDIRECT_TO_SCANNED_EXTENSIONLESS:
					redirectPermanent(response, getExtensionlessURLWithQuery(request, reverseResources.get(resource)));
					return true;
				case SEND_404:
					response.sendError(SC_NOT_FOUND);
					return true;
				case PROCEED:
					break;
				default:
					break;
			}
		}

		return false;
	}

	private static void redirectPermanent(HttpServletResponse response, String url) {
		response.setStatus(SC_MOVED_PERMANENTLY);
		response.setHeader("Location", url);
		response.setHeader("Connection", "close");
	}

}