/*******************************************************************************
 * Copyright (c) 2000, 2004 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Common Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/cpl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.swt.snippets;

/*
 * Table example snippet: find a table cell from mouse down (works for any table style)
 *
 * For a list of all SWT example snippets see
 * http://dev.eclipse.org/viewcvs/index.cgi/%7Echeckout%7E/platform-swt-home/dev.html#snippets
 */
import org.eclipse.swt.*;
import org.eclipse.swt.widgets.*;
import org.eclipse.swt.graphics.*;

public class Snippet110 {

public static void main (String [] args) {
	Display display = new Display ();
	Shell shell = new Shell (display);
	final Table table = new Table (shell, SWT.BORDER | SWT.V_SCROLL);
	table.setHeaderVisible (true);
	table.setLinesVisible (true);
	final int rowCount = 64, columnCount = 4;
	for (int i=0; i < columnCount; i++) {
		TableColumn column = new TableColumn (table, SWT.NULL);
		column.setText ("Column " + i);
	}
	for (int i=0; i < rowCount; i++) {
		TableItem item = new TableItem (table, SWT.NULL);
		for (int j=0; j < columnCount; j++) {
			item.setText (j, "Item " + i + "-" + j);
		}
	}
	for (int i=0; i < columnCount; i++) {
		table.getColumn (i).pack ();
	}
	Point size = table.computeSize (SWT.DEFAULT, 200);
	table.setSize (size);
	shell.pack ();
	table.addListener (SWT.MouseDown, new Listener () {
		public void handleEvent (Event event) {
			Rectangle clientArea = table.getClientArea ();
			Point pt = new Point (event.x, event.y);
			int index = table.getTopIndex ();
			while (index < table.getItemCount ()) {
				boolean visible = false;
				TableItem item = table.getItem (index);
				for (int i=0; i < columnCount; i++) {
					Rectangle rect = item.getBounds (i);
					if (rect.contains (pt)) {
						System.out.println ("Item " + index + "-" + i);
					}
					if (!visible && rect.intersects (clientArea)) {
						visible = true;
					}
				}
				if (!visible) return;
				index++;
			}
		}
	});
	shell.open ();
	while (!shell.isDisposed()) {
		if (!display.readAndDispatch ()) display.sleep ();
	}
	display.dispose ();
}
}
