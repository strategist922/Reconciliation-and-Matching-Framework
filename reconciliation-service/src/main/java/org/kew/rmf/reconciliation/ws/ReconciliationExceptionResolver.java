/*
 * Reconciliation and Matching Framework
 * Copyright © 2014 Royal Botanic Gardens, Kew
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.kew.rmf.reconciliation.ws;

import java.text.SimpleDateFormat;
import java.util.Date;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.handler.SimpleMappingExceptionResolver;

/**
 * Adds the date, URL and contact email address details to exception messages.  (Also, menu and breadcrumbs.)
 */
public class ReconciliationExceptionResolver extends SimpleMappingExceptionResolver {

	@Autowired
	private BaseController baseController;

	private SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z");

	@Override
	protected ModelAndView doResolveException(HttpServletRequest request, HttpServletResponse response, Object handler, Exception exception) {
		ModelAndView mav = super.doResolveException(request, response, handler, exception);

		mav.addObject("url", request.getRequestURL());
		mav.addObject("datetime", simpleDateFormat.format(new Date()));
		mav.addObject("contactemail", "apps-support@kew.org");
		mav.addObject("statusCode", response.getStatus());
		mav.addObject("statusMessage", HttpStatus.valueOf(response.getStatus()).getReasonPhrase());
		baseController.menuAndBreadcrumbs("/#", mav);

		return mav;
	}
}
