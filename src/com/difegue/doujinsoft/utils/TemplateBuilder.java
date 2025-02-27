package com.difegue.doujinsoft.utils;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.reflect.Constructor;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import com.google.gson.Gson;

import com.difegue.doujinsoft.templates.*;
import com.difegue.doujinsoft.utils.MioUtils.Types;
import com.mitchellbosecke.pebble.PebbleEngine;
import com.mitchellbosecke.pebble.error.PebbleException;
import com.mitchellbosecke.pebble.template.PebbleTemplate;

/*
 * This class contains generic get/search methods used across all three major servlets
 * (Games, Comics and Records)
 */
public class TemplateBuilder {

	protected ArrayList items = new ArrayList();
	protected Constructor classConstructor;

	protected Map<String, Object> context = new HashMap<>();
	protected Connection connection;
	protected Statement statement;
	protected ServletContext application;
	protected HttpServletRequest request;

	protected String tableName, dataDir;
	protected boolean isNameSearch, isCreatorSearch;
	
	protected PebbleEngine engine = new PebbleEngine.Builder().build();
	protected PebbleTemplate compiledTemplate;

	public TemplateBuilder(ServletContext application, HttpServletRequest request) throws SQLException {

		this.application = application;
		this.request = request;
		dataDir = application.getInitParameter("dataDirectory");

	    // create a database connection
	    connection = DriverManager.getConnection("jdbc:sqlite:"+dataDir+"/mioDatabase.sqlite");
	    statement = connection.createStatement();
		statement.setQueryTimeout(30);  // set timeout to 30 sec.
	}

	protected void initializeTemplate(int type, boolean isDetail) throws NoSuchMethodException, PebbleException {

		String templatePath = "/WEB-INF/templates/";
		//Getting base template and other type dependant data
		//If in search mode, we only use the part of the template containing the item cards 
		switch (type) {
			case Types.GAME:
				templatePath += "game";
				tableName = "Games";
				classConstructor = Game.class.getConstructor(ResultSet.class);
				break;
			case Types.MANGA:
				templatePath += "manga";
				tableName = "Manga";
				classConstructor = Manga.class.getConstructor(ResultSet.class);
				break;
			case Types.RECORD:
				templatePath += "records";
				tableName = "Records";
				classConstructor = Record.class.getConstructor(ResultSet.class);
				break;
			case Types.SURVEY:
				templatePath += "surveys";
				tableName = "Surveys";
				classConstructor = Survey.class.getConstructor(ResultSet.class);
			}

		if (isDetail) {
			templatePath   += "Detail";	
			isNameSearch    = request.getParameterMap().containsKey("name") && !request.getParameter("name").isEmpty();
			isCreatorSearch = request.getParameterMap().containsKey("creator") && !request.getParameter("creator").isEmpty();
		}
			
		compiledTemplate = engine.getTemplate(application.getRealPath(templatePath+".html"));
	}

	protected String writeToTemplate() throws PebbleException, IOException {

		Writer writer = new StringWriter();
		compiledTemplate.evaluate(writer, context);
		String output = writer.toString();
		
		return output;
	}

	/*
	 * For GET requests. Grab the standard template, and add the first page of items.
	 */
	public String doStandardPageGeneric(int type) throws Exception {

		initializeTemplate(type, false);
		ResultSet result;
		if (request.getParameterMap().containsKey("id"))
			result = statement.executeQuery("select * from "+tableName+" WHERE hash LIKE '"+request.getParameter("id")+"'");
		else
  			result = statement.executeQuery("select * from "+tableName+" WHERE id NOT LIKE '%nint%' AND id NOT LIKE '%them%' ORDER BY normalizedName ASC LIMIT 15");
  		
  		while(result.next()) 
	    		items.add(classConstructor.newInstance(result));
  		
		context.put("items", items);
		
		// If the request is for a specific hash, disable search by setting totalitems to -1
		if (request.getParameterMap().containsKey("id")) {
			context.put("totalitems", -1);	
			context.put("singleitem", true);
		} else {	
			result = statement.executeQuery("select COUNT(id) from "+tableName+" WHERE id NOT LIKE '%nint%' AND id NOT LIKE '%them%'");
			context.put("totalitems", result.getInt(1));
		}

		// JSON hijack if specified in the parameters
		if (request.getParameterMap().containsKey("format") && request.getParameter("format").equals("json")) {
			Gson gson = new Gson();
			return gson.toJson(context);
		}

		//Output to client
		return writeToTemplate();
	}

	/*
	 * For POST requests. Perform a request based on the parameters given (search and/or pages) and return the matching subtemplate.
	 */
	public String doSearchGeneric(int type) throws Exception {

		initializeTemplate(type, true);
		
		// Build both data and count queries
	    String queryBase = "FROM "+tableName+" WHERE ";
	    queryBase += (isNameSearch || isCreatorSearch) ? "name LIKE ? AND creator LIKE ? AND ": "";
	    queryBase += "id NOT LIKE '%nint%' AND id NOT LIKE '%them%'";
		
	    String query = "SELECT * " + queryBase + " ORDER BY normalizedName ASC LIMIT 15 OFFSET ?";
	    String queryCount = "SELECT COUNT(id) " + queryBase;
		
		PreparedStatement ret = connection.prepareStatement(query);	
		PreparedStatement retCount = connection.prepareStatement(queryCount);

		//Those filters go in the LIKE parts of the query
		String name    = isNameSearch ? request.getParameter("name")+"%" : "%";
		String creator = isCreatorSearch ? request.getParameter("creator")+"%" : "%";

		int page = 1;
		if (request.getParameterMap().containsKey("page") && !request.getParameter("page").isEmpty())
			page = Integer.parseInt(request.getParameter("page"));
		
		if (isNameSearch || isCreatorSearch) {
			ret.setString(1, name);
			ret.setString(2, creator);
			retCount.setString(1, name);
		    retCount.setString(2, creator);
			ret.setInt(3, page*15-15);
		} else 
			ret.setInt(1, page*15-15);
    	
		ResultSet result = ret.executeQuery();
	    
	    while(result.next()) 
	    	items.add(classConstructor.newInstance(result));

		context.put("items", items);
		context.put("totalitems", retCount.executeQuery().getInt(1));

		// JSON hijack if specified in the parameters
		if (request.getParameterMap().containsKey("format") && request.getParameter("format").equals("json")) {
			Gson gson = new Gson();
			return gson.toJson(context);
		}

		return writeToTemplate();
    }
	
}
