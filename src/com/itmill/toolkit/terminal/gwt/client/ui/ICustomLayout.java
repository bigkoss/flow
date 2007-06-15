package com.itmill.toolkit.terminal.gwt.client.ui;

import java.util.HashMap;
import java.util.Iterator;

import com.google.gwt.user.client.DOM;
import com.google.gwt.user.client.Element;
import com.google.gwt.user.client.ui.HTMLPanel;
import com.google.gwt.user.client.ui.SimplePanel;
import com.google.gwt.user.client.ui.VerticalPanel;
import com.google.gwt.user.client.ui.Widget;
import com.itmill.toolkit.terminal.gwt.client.CaptionWrapper;
import com.itmill.toolkit.terminal.gwt.client.Client;
import com.itmill.toolkit.terminal.gwt.client.Layout;
import com.itmill.toolkit.terminal.gwt.client.Paintable;
import com.itmill.toolkit.terminal.gwt.client.UIDL;

public class ICustomLayout extends SimplePanel implements Paintable, Layout {

	private HashMap componentToWrapper = new HashMap();
	HTMLPanel html;
	String currentStyle;
	String locationPrefix = HTMLPanel.createUniqueId() + "_";
	
	public void updateFromUIDL(UIDL uidl, Client client) {
		
		if (client.updateComponent(this, uidl, false)) return;
		
		updateHTML(uidl, client);
		
		componentToWrapper.clear();
		
		for (Iterator i = uidl.getChildIterator(); i.hasNext();) {
			UIDL uidlForChild = (UIDL) i.next();
			if (uidlForChild.getTag().equals("location")) {
				String location = uidlForChild.getStringAttribute("name");
				Widget child = client.getWidget(uidlForChild.getChildUIDL(0));
				try {
				html.add(child,locationPrefix + location);
				} catch(Exception e) {
					// If no location is found, this component is not visible
				}
				((Paintable)child).updateFromUIDL(uidlForChild.getChildUIDL(0), client);
				
			}
		}
	}

	private void updateHTML(UIDL uidl, Client client) {
		String newStyle = uidl.getStringAttribute("style");
		if (currentStyle != null && currentStyle.equals(newStyle)) return;
		
			String template = client.getResource("layout/"+newStyle+".html");
			if (template == null) {
				template = "Layout " + newStyle + " is missing";
			} else {
				currentStyle = newStyle;
			}
			html = new HTMLPanel(template);
			add(html);
			
			addUniqueIdsForLocations(html.getElement(), locationPrefix);
			
			Widget parent = getParent();
			while (parent != null && !(parent instanceof IWindow)) parent = parent.getParent();
			if (parent != null && ((IWindow)parent).getTheme() != null);
			prefixImgSrcs(html.getElement(), "../theme/"+((IWindow)parent).getTheme()+"/layout/");
	}

	private native void addUniqueIdsForLocations(Element e, String idPrefix) /*-{
		var divs = e.getElementsByTagName("div"); 
		for (var i = 0; i < divs.length; i++) {
		 	var div = divs[i];
		 	var location = div.getAttribute("location");
			if (location != null) div.setAttribute("id",idPrefix + location);
		}			
	}-*/;

	private native void prefixImgSrcs(Element e, String srcPrefix) /*-{
	var divs = e.getElementsByTagName("img"); 
	for (var i = 0; i < divs.length; i++) {
	 	var div = divs[i];
	 	var src = div.getAttribute("src");
		if (src.indexOf("http") != 0) div.setAttribute("src",srcPrefix + src);
	}			
}-*/;

	public void replaceChildComponent(Widget from, Widget to) {
		CaptionWrapper wrapper = (CaptionWrapper) componentToWrapper.get(from);
		if (wrapper != null) {
			componentToWrapper.remove(from);
			from = wrapper;
		}
		// TODO
		html.remove(from);
		html.add(to);

	}

	public boolean hasChildComponent(Widget component) {
		// TODO
		return componentToWrapper.get(component) != null;
	}

	public void updateCaption(Widget component, UIDL uidl) {
		// TODO
		/*
		CaptionWrapper wrapper = (CaptionWrapper) componentToWrapper.get(component);
		if (CaptionWrapper.isNeeded(uidl)) {
			if (wrapper == null) {
				int index = getWidgetIndex(component);
				remove(component);
				wrapper = new CaptionWrapper(component);
				insert(wrapper, index);
				componentToWrapper.put(component, wrapper);
			}
			wrapper.updateCaption(uidl);
		} else {
			if (wrapper != null) { 
				int index = getWidgetIndex(wrapper);
				remove(wrapper);
				insert(wrapper.getWidget(), index);
				componentToWrapper.remove(component);
			}
		}
		*/
	}
	
	

}
