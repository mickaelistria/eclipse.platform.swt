/*******************************************************************************
 * Copyright (c) 2000, 2004 IBM Corporation and others. All rights reserved.
 * The contents of this file are made available under the terms
 * of the GNU Lesser General Public License (LGPL) Version 2.1 that
 * accompanies this distribution (lgpl-v21.txt).  The LGPL is also
 * available at http://www.gnu.org/licenses/lgpl.html.  If the version
 * of the LGPL at http://www.gnu.org is different to the version of
 * the LGPL accompanying this distribution and there is any conflict
 * between the two license versions, the terms of the LGPL accompanying
 * this distribution shall govern.
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
  
#ifndef INC_os_H
#define INC_os_H

#define NDEBUG

/*
#define G_DISABLE_DEPRECATED
#define GTK_DISABLE_DEPRECATED
*/

#include <gtk/gtk.h>
#include <gdk/gdk.h>
#include <pango/pango.h>
#include <pango/pango-font.h>
#include <string.h>
#include <dlfcn.h>

#ifndef GDK_WINDOWING_X11

/* X Structures */
#define NO_XClientMessageEvent
#define NO_XCrossingEvent
#define NO_XExposeEvent
#define NO_XFocusChangeEvent
#define NO_XVisibilityEvent
#define NO_XWindowChanges

/* X functions */
#define NO_XCheckMaskEvent
#define NO_XCheckWindowEvent
#define NO_XCheckIfEvent
#define NO_XDefaultScreen
#define NO_XDefaultRootWindow
#define NO_XGetSelectionOwner
#define NO_XQueryTree
#define NO_XKeysymToKeycode
#define NO_XReconfigureWMWindow
#define NO_XSendEvent
#define NO_XSetInputFocus
#define NO_XSynchronize
#define NO_XSetErrorHandler
#define NO_XSetIOErrorHandler
#define NO_XTestFakeButtonEvent
#define NO_XTestFakeKeyEvent
#define NO_XTestFakeMotionEvent
#define NO_XWarpPointer
#define NO_gdk_x11_atom_to_xatom
#define NO_gdk_1x11_1drawable_1get_1xdisplay
#define NO_gdk_1x11_1drawable_1get_1xid
#define NO_gdk_window_lookup
#define NO_gdk_window_add_filter
#define NO_memmove__ILorg_eclipse_swt_internal_gtk_XClientMessageEvent_2I
#define NO_memmove__ILorg_eclipse_swt_internal_gtk_XCrossingEvent_2I
#define NO_memmove__ILorg_eclipse_swt_internal_gtk_XExposeEvent_2I
#define NO_memmove__ILorg_eclipse_swt_internal_gtk_XFocusChangeEvent_2I
#define NO_memmove__Lorg_eclipse_swt_internal_gtk_XCrossingEvent_2II
#define NO_memmove__Lorg_eclipse_swt_internal_gtk_XExposeEvent_2II
#define NO_memmove__Lorg_eclipse_swt_internal_gtk_XFocusChangeEvent_2II
#define NO_memmove__Lorg_eclipse_swt_internal_gtk_XVisibilityEvent_2II

#else
#include <gdk/gdkx.h>
#include <X11/extensions/XTest.h>
#endif

#include "os_custom.h"

#endif /* INC_os_H */
