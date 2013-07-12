/*******************************************************************************
 * Copyright (c) 2000, 2013 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.swt.widgets;


import org.eclipse.swt.*;
import org.eclipse.swt.internal.*;
import org.eclipse.swt.internal.cairo.*;
import org.eclipse.swt.internal.gtk.*;
import org.eclipse.swt.graphics.*;

/**
 * Instances of this class are responsible for managing the
 * connection between SWT and the underlying operating
 * system. Their most important function is to implement
 * the SWT event loop in terms of the platform event model.
 * They also provide various methods for accessing information
 * about the operating system, and have overall control over
 * the operating system resources which SWT allocates.
 * <p>
 * Applications which are built with SWT will <em>almost always</em>
 * require only a single display. In particular, some platforms
 * which SWT supports will not allow more than one <em>active</em>
 * display. In other words, some platforms do not support
 * creating a new display if one already exists that has not been
 * sent the <code>dispose()</code> message.
 * <p>
 * In SWT, the thread which creates a <code>Display</code>
 * instance is distinguished as the <em>user-interface thread</em>
 * for that display.
 * </p>
 * The user-interface thread for a particular display has the
 * following special attributes:
 * <ul>
 * <li>
 * The event loop for that display must be run from the thread.
 * </li>
 * <li>
 * Some SWT API methods (notably, most of the public methods in
 * <code>Widget</code> and its subclasses), may only be called
 * from the thread. (To support multi-threaded user-interface
 * applications, class <code>Display</code> provides inter-thread
 * communication methods which allow threads other than the 
 * user-interface thread to request that it perform operations
 * on their behalf.)
 * </li>
 * <li>
 * The thread is not allowed to construct other 
 * <code>Display</code>s until that display has been disposed.
 * (Note that, this is in addition to the restriction mentioned
 * above concerning platform support for multiple displays. Thus,
 * the only way to have multiple simultaneously active displays,
 * even on platforms which support it, is to have multiple threads.)
 * </li>
 * </ul>
 * Enforcing these attributes allows SWT to be implemented directly
 * on the underlying operating system's event model. This has 
 * numerous benefits including smaller footprint, better use of 
 * resources, safer memory management, clearer program logic,
 * better performance, and fewer overall operating system threads
 * required. The down side however, is that care must be taken
 * (only) when constructing multi-threaded applications to use the
 * inter-thread communication mechanisms which this class provides
 * when required.
 * </p><p>
 * All SWT API methods which may only be called from the user-interface
 * thread are distinguished in their documentation by indicating that
 * they throw the "<code>ERROR_THREAD_INVALID_ACCESS</code>"
 * SWT exception.
 * </p>
 * <dl>
 * <dt><b>Styles:</b></dt>
 * <dd>(none)</dd>
 * <dt><b>Events:</b></dt>
 * <dd>Close, Dispose, OpenDocument, Settings, Skin</dd>
 * </dl>
 * <p>
 * IMPORTANT: This class is <em>not</em> intended to be subclassed.
 * </p>
 * @see #syncExec
 * @see #asyncExec
 * @see #wake
 * @see #readAndDispatch
 * @see #sleep
 * @see Device#dispose
 * @see <a href="http://www.eclipse.org/swt/snippets/#display">Display snippets</a>
 * @see <a href="http://www.eclipse.org/swt/">Sample code and further information</a>
 * @noextend This class is not intended to be subclassed by clients.
 */
public class Display extends Device {

	/* Events Dispatching and Callback */
	int gdkEventCount;
	long /*int*/ [] gdkEvents;
	Widget [] gdkEventWidgets;
	int [] dispatchEvents;
	Event [] eventQueue;
	long /*int*/ fds;
	int allocated_nfds;
	boolean wake;
	int [] max_priority = new int [1], timeout = new int [1];
	Callback eventCallback, filterCallback;
	long /*int*/ eventProc, filterProc, windowProc2, windowProc3, windowProc4, windowProc5;
	Callback windowCallback2, windowCallback3, windowCallback4, windowCallback5;
	EventTable eventTable, filterTable;
	static String APP_NAME = "SWT"; //$NON-NLS-1$
	static String APP_VERSION = ""; //$NON-NLS-1$
	static final String DISPATCH_EVENT_KEY = "org.eclipse.swt.internal.gtk.dispatchEvent"; //$NON-NLS-1$
	static final String ADD_WIDGET_KEY = "org.eclipse.swt.internal.addWidget"; //$NON-NLS-1$
	long /*int*/ [] closures;
	int [] signalIds;
	long /*int*/ shellMapProcClosure;

	/* Widget Table */
	int [] indexTable;
	int freeSlot;
	long /*int*/ lastHandle;
	Widget lastWidget;
	Widget [] widgetTable;
	final static int GROW_SIZE = 1024;
	static final int SWT_OBJECT_INDEX;
	static final int SWT_OBJECT_INDEX1;
	static final int SWT_OBJECT_INDEX2;
	static {
		byte [] buffer = Converter.wcsToMbcs (null, "SWT_OBJECT_INDEX", true); //$NON-NLS-1$
		SWT_OBJECT_INDEX = OS.g_quark_from_string (buffer);
		buffer = Converter.wcsToMbcs (null, "SWT_OBJECT_INDEX1", true); //$NON-NLS-1$
		SWT_OBJECT_INDEX1 = OS.g_quark_from_string (buffer);
		buffer = Converter.wcsToMbcs (null, "SWT_OBJECT_INDEX2", true); //$NON-NLS-1$
		SWT_OBJECT_INDEX2 = OS.g_quark_from_string (buffer);
	}

	/* Modality */
	Shell [] modalShells;
	Dialog modalDialog;
	static final String GET_MODAL_DIALOG = "org.eclipse.swt.internal.gtk.getModalDialog"; //$NON-NLS-1$
	static final String SET_MODAL_DIALOG = "org.eclipse.swt.internal.gtk.setModalDialog"; //$NON-NLS-1$

	/* Focus */
	int focusEvent;
	Control focusControl;
	Shell activeShell;
	boolean activePending;
	boolean ignoreActivate, ignoreFocus;
	
	Tracker tracker;

	/* Input method resources */
	Control imControl;
	long /*int*/ preeditWindow, preeditLabel;

	/* Sync/Async Widget Communication */
	Synchronizer synchronizer = new Synchronizer (this);
	Thread thread;

	/* Display Shutdown */
	Runnable [] disposeList;
	
	/* Deferred Layout list */
	Composite[] layoutDeferred;
	int layoutDeferredCount;

	/* System Tray */
	Tray tray;
	TrayItem currentTrayItem;
	
	/* Timers */
	int [] timerIds;
	Runnable [] timerList;
	Callback timerCallback;
	long /*int*/ timerProc;
	Callback windowTimerCallback;
	long /*int*/ windowTimerProc;
	
	/* Caret */
	Caret currentCaret;
	Callback caretCallback;
	int caretId;
	long /*int*/ caretProc;
	
	/* Mnemonics */
	Control mnemonicControl;

	/* Mouse hover */
	int mouseHoverId;
	long /*int*/ mouseHoverHandle, mouseHoverProc;
	Callback mouseHoverCallback;
	
	/* Menu position callback */
	long /*int*/ menuPositionProc;
	Callback menuPositionCallback;

	/* Tooltip size allocate callback */
	long /*int*/ sizeAllocateProc;
	Callback sizeAllocateCallback;
	long /*int*/ sizeRequestProc;
	Callback sizeRequestCallback;

	/* Shell map callback */
	long /*int*/ shellMapProc;
	Callback shellMapCallback;
	
	/* Idle proc callback */
	long /*int*/ idleProc;
	int idleHandle;
	Callback idleCallback;
	static final String ADD_IDLE_PROC_KEY = "org.eclipse.swt.internal.gtk.addIdleProc"; //$NON-NLS-1$
	static final String REMOVE_IDLE_PROC_KEY = "org.eclipse.swt.internal.gtk.removeIdleProc"; //$NON-NLS-1$
	Object idleLock = new Object();
	boolean idleNeeded;
	
	/* GtkTreeView callbacks */
	long /*int*/ cellDataProc;
	Callback cellDataCallback;
	
	/* Set direction callback */
	long /*int*/ setDirectionProc;
	Callback setDirectionCallback;
	static final String GET_DIRECTION_PROC_KEY = "org.eclipse.swt.internal.gtk.getDirectionProc"; //$NON-NLS-1$
	
	/* Set emissionProc callback */
	long /*int*/ emissionProc;
	Callback emissionProcCallback;
	static final String GET_EMISSION_PROC_KEY = "org.eclipse.swt.internal.gtk.getEmissionProc"; //$NON-NLS-1$
	
	/* Get all children callback */
	long /*int*/ allChildrenProc, allChildren;
	Callback allChildrenCallback;

	/* Settings callbacks */
	long /*int*/ signalProc;
	Callback signalCallback;
	long /*int*/ shellHandle;
	boolean settingsChanged, runSettings;
	static final int STYLE_SET = 1;
	static final int PROPERTY_NOTIFY = 2;
	
	/* Entry focus behaviour */
	boolean entrySelectOnFocus;
	
	/* Enter/Exit events */
	Control currentControl;
	
	/* Flush exposes */
	long /*int*/ checkIfEventProc;
	Callback checkIfEventCallback;
	long /*int*/ flushWindow;
	boolean flushAll;
	GdkRectangle flushRect = new GdkRectangle ();
	XExposeEvent exposeEvent = new XExposeEvent ();
	XVisibilityEvent visibilityEvent = new XVisibilityEvent ();
	long /*int*/ [] flushData = new long /*int*/ [1];

	/* System Resources */
	Image errorImage, infoImage, questionImage, warningImage;
	Cursor [] cursors = new Cursor [SWT.CURSOR_HAND + 1];
	Resource [] resources;
	static final int RESOURCE_SIZE = 1 + 4 + SWT.CURSOR_HAND + 1;

	/* Colors */
	GdkColor COLOR_WIDGET_DARK_SHADOW, COLOR_WIDGET_NORMAL_SHADOW, COLOR_WIDGET_LIGHT_SHADOW;
	GdkColor COLOR_WIDGET_HIGHLIGHT_SHADOW, COLOR_WIDGET_BACKGROUND, COLOR_WIDGET_FOREGROUND, COLOR_WIDGET_BORDER;
	GdkColor COLOR_LIST_FOREGROUND, COLOR_LIST_BACKGROUND, COLOR_LIST_SELECTION, COLOR_LIST_SELECTION_TEXT;
	GdkColor COLOR_LIST_SELECTION_INACTIVE, COLOR_LIST_SELECTION_TEXT_INACTIVE;
	GdkColor COLOR_INFO_BACKGROUND, COLOR_INFO_FOREGROUND, COLOR_LINK_FOREGROUND;
	GdkColor COLOR_TITLE_FOREGROUND, COLOR_TITLE_BACKGROUND, COLOR_TITLE_BACKGROUND_GRADIENT;
	GdkColor COLOR_TITLE_INACTIVE_FOREGROUND, COLOR_TITLE_INACTIVE_BACKGROUND, COLOR_TITLE_INACTIVE_BACKGROUND_GRADIENT;

	/* Popup Menus */
	Menu [] popups;
	
	/* Click count*/
	int clickCount = 1;
	
	/* Entry inner border */
	static final int INNER_BORDER = 2;
	
	/* Timestamp of the Last Received Events */
	int lastEventTime, lastUserEventTime;
	
	/* Pango layout constructor */
	long /*int*/ pangoLayoutNewProc;
	
	/* IM Context constructor */
	long /*int*/ imContextNewProc;
	
	/* Custom Resize */
	double resizeLocationX, resizeLocationY;
	int resizeBoundsX, resizeBoundsY, resizeBoundsWidth, resizeBoundsHeight;
	int resizeMode;
	
	/* Fixed Subclass */
	static long /*int*/ fixed_type;
	static long /*int*/ fixed_info_ptr;
	static Callback fixedClassInitCallback, fixedMapCallback, fixedSizeAllocateCallback;
	static long /*int*/ fixedClassInitProc, fixedMapProc, fixedSizeAllocateProc, oldFixedSizeAllocateProc;

	/* Renderer Subclass */
	static long /*int*/ text_renderer_type, pixbuf_renderer_type, toggle_renderer_type;
	static long /*int*/ text_renderer_info_ptr, pixbuf_renderer_info_ptr, toggle_renderer_info_ptr;
	static Callback rendererClassInitCallback, rendererRenderCallback, rendererGetSizeCallback;
	static Callback rendererGetPreferredWidthCallback;
	static long /*int*/ rendererClassInitProc, rendererRenderProc, rendererGetSizeProc;
	static long /*int*/ rendererGetPreferredWidthProc;

	/* Key Mappings */
	static final int [] [] KeyTable = {
		
		/* Keyboard and Mouse Masks */
		{OS.GDK_Alt_L,		SWT.ALT},
		{OS.GDK_Alt_R,		SWT.ALT},
		{OS.GDK_Meta_L,	SWT.ALT},
		{OS.GDK_Meta_R,	SWT.ALT},
		{OS.GDK_Shift_L,		SWT.SHIFT},
		{OS.GDK_Shift_R,		SWT.SHIFT},
		{OS.GDK_Control_L,	SWT.CONTROL},
		{OS.GDK_Control_R,	SWT.CONTROL},
//		{OS.GDK_????,		SWT.COMMAND},
//		{OS.GDK_????,		SWT.COMMAND},
		
		/* Non-Numeric Keypad Keys */
		{OS.GDK_Up,						SWT.ARROW_UP},
		{OS.GDK_KP_Up,					SWT.ARROW_UP},
		{OS.GDK_Down,					SWT.ARROW_DOWN},
		{OS.GDK_KP_Down,			SWT.ARROW_DOWN},
		{OS.GDK_Left,						SWT.ARROW_LEFT},
		{OS.GDK_KP_Left,				SWT.ARROW_LEFT},
		{OS.GDK_Right,					SWT.ARROW_RIGHT},
		{OS.GDK_KP_Right,				SWT.ARROW_RIGHT},
		{OS.GDK_Page_Up,				SWT.PAGE_UP},
		{OS.GDK_KP_Page_Up,		SWT.PAGE_UP},
		{OS.GDK_Page_Down,			SWT.PAGE_DOWN},
		{OS.GDK_KP_Page_Down,	SWT.PAGE_DOWN},
		{OS.GDK_Home,					SWT.HOME},
		{OS.GDK_KP_Home,			SWT.HOME},
		{OS.GDK_End,						SWT.END},
		{OS.GDK_KP_End,				SWT.END},
		{OS.GDK_Insert,					SWT.INSERT},
		{OS.GDK_KP_Insert,			SWT.INSERT},
		
		/* Virtual and Ascii Keys */
		{OS.GDK_BackSpace,		SWT.BS},
		{OS.GDK_Return,				SWT.CR},
		{OS.GDK_Delete,				SWT.DEL},
		{OS.GDK_KP_Delete,		SWT.DEL},
		{OS.GDK_Escape,			SWT.ESC},
		{OS.GDK_Linefeed,			SWT.LF},
		{OS.GDK_Tab,					SWT.TAB},
		{OS.GDK_ISO_Left_Tab, 	SWT.TAB},
	
		/* Functions Keys */
		{OS.GDK_F1,		SWT.F1},
		{OS.GDK_F2,		SWT.F2},
		{OS.GDK_F3,		SWT.F3},
		{OS.GDK_F4,		SWT.F4},
		{OS.GDK_F5,		SWT.F5},
		{OS.GDK_F6,		SWT.F6},
		{OS.GDK_F7,		SWT.F7},
		{OS.GDK_F8,		SWT.F8},
		{OS.GDK_F9,		SWT.F9},
		{OS.GDK_F10,		SWT.F10},
		{OS.GDK_F11,		SWT.F11},
		{OS.GDK_F12,		SWT.F12},
		{OS.GDK_F13,		SWT.F13},
		{OS.GDK_F14,		SWT.F14},
		{OS.GDK_F15,		SWT.F15},
		{OS.GDK_F16,		SWT.F16},
		{OS.GDK_F17,		SWT.F17},
		{OS.GDK_F18,		SWT.F18},
		{OS.GDK_F19,		SWT.F19},
		{OS.GDK_F20,		SWT.F20},
		
		/* Numeric Keypad Keys */
		{OS.GDK_KP_Multiply,		SWT.KEYPAD_MULTIPLY},
		{OS.GDK_KP_Add,			SWT.KEYPAD_ADD},
		{OS.GDK_KP_Enter,			SWT.KEYPAD_CR},
		{OS.GDK_KP_Subtract,	SWT.KEYPAD_SUBTRACT},
		{OS.GDK_KP_Decimal,	SWT.KEYPAD_DECIMAL},
		{OS.GDK_KP_Divide,		SWT.KEYPAD_DIVIDE},
		{OS.GDK_KP_0,			SWT.KEYPAD_0},
		{OS.GDK_KP_1,			SWT.KEYPAD_1},
		{OS.GDK_KP_2,			SWT.KEYPAD_2},
		{OS.GDK_KP_3,			SWT.KEYPAD_3},
		{OS.GDK_KP_4,			SWT.KEYPAD_4},
		{OS.GDK_KP_5,			SWT.KEYPAD_5},
		{OS.GDK_KP_6,			SWT.KEYPAD_6},
		{OS.GDK_KP_7,			SWT.KEYPAD_7},
		{OS.GDK_KP_8,			SWT.KEYPAD_8},
		{OS.GDK_KP_9,			SWT.KEYPAD_9},
		{OS.GDK_KP_Equal,	SWT.KEYPAD_EQUAL},

		/* Other keys */
		{OS.GDK_Caps_Lock,		SWT.CAPS_LOCK},
		{OS.GDK_Num_Lock,		SWT.NUM_LOCK},
		{OS.GDK_Scroll_Lock,		SWT.SCROLL_LOCK},
		{OS.GDK_Pause,				SWT.PAUSE},
		{OS.GDK_Break,				SWT.BREAK},
		{OS.GDK_Print,					SWT.PRINT_SCREEN},
		{OS.GDK_Help,					SWT.HELP},
		
	};

	/* Multiple Displays. */
	static Display Default;
	static Display [] Displays = new Display [4];

	/* Skinning support */
	Widget [] skinList = new Widget [GROW_SIZE];
	int skinCount;
	
	/* Package name */
	static final String PACKAGE_PREFIX = "org.eclipse.swt.widgets."; //$NON-NLS-1$
	/* This code is intentionally commented.
	 * ".class" can not be used on CLDC.
	 */
//	static {
//		String name = Display.class.getName ();
//		int index = name.lastIndexOf ('.');
//		PACKAGE_NAME = name.substring (0, index + 1);
//	}

	/* GTK Version */
	static final int GTK3_MAJOR = 3;
	static final int GTK3_MINOR = 0;
	static final int GTK3_MICRO = 0;
	static final int GTK2_MAJOR = 2;
	static final int GTK2_MINOR = 10;
	static final int GTK2_MICRO = 0;

	/* Display Data */
	Object data;
	String [] keys;
	Object [] values;
	
	/* Initial Guesses for Shell Trimmings. */
	int borderTrimWidth = 4, borderTrimHeight = 4;
	int resizeTrimWidth = 6, resizeTrimHeight = 6;
	int titleBorderTrimWidth = 5, titleBorderTrimHeight = 28;
	int titleResizeTrimWidth = 6, titleResizeTrimHeight = 29;
	int titleTrimWidth = 0, titleTrimHeight = 23;
	boolean ignoreTrim;

	/* Window Manager */
	String windowManager;

	/*
	* TEMPORARY CODE.  Install the runnable that
	* gets the current display. This code will
	* be removed in the future.
	*/
	static {
		DeviceFinder = new Runnable () {
			public void run () {
				Device device = getCurrent ();
				if (device == null) {
					device = getDefault ();
				}
				setDevice (device);
			}
		};
	}

/*
* TEMPORARY CODE.
*/
static void setDevice (Device device) {
	CurrentDevice = device;
}

/**
 * Constructs a new instance of this class.
 * <p>
 * Note: The resulting display is marked as the <em>current</em>
 * display. If this is the first display which has been 
 * constructed since the application started, it is also
 * marked as the <em>default</em> display.
 * </p>
 *
 * @exception SWTException <ul>
 *    <li>ERROR_THREAD_INVALID_ACCESS - if called from a thread that already created an existing display</li>
 *    <li>ERROR_INVALID_SUBCLASS - if this class is not an allowed subclass</li>
 * </ul>
 *
 * @see #getCurrent
 * @see #getDefault
 * @see Widget#checkSubclass
 * @see Shell
 */
public Display () {
	this (null);
}

/**
 * Constructs a new instance of this class using the parameter.
 * 
 * @param data the device data
 */
public Display (DeviceData data) {
	super (data);
}

/**
 * Adds the listener to the collection of listeners who will
 * be notified when an event of the given type occurs anywhere
 * in a widget. The event type is one of the event constants
 * defined in class <code>SWT</code>. When the event does occur,
 * the listener is notified by sending it the <code>handleEvent()</code>
 * message.
 * <p>
 * Setting the type of an event to <code>SWT.None</code> from
 * within the <code>handleEvent()</code> method can be used to
 * change the event type and stop subsequent Java listeners
 * from running. Because event filters run before other listeners,
 * event filters can both block other listeners and set arbitrary
 * fields within an event. For this reason, event filters are both
 * powerful and dangerous. They should generally be avoided for
 * performance, debugging and code maintenance reasons.
 * </p>
 * 
 * @param eventType the type of event to listen for
 * @param listener the listener which should be notified when the event occurs
 *
 * @exception IllegalArgumentException <ul>
 *    <li>ERROR_NULL_ARGUMENT - if the listener is null</li>
 * </ul>
 * @exception SWTException <ul>
 *    <li>ERROR_THREAD_INVALID_ACCESS - if not called from the thread that created the receiver</li>
 *    <li>ERROR_DEVICE_DISPOSED - if the receiver has been disposed</li>
 * </ul>
 *
 * @see Listener
 * @see SWT
 * @see #removeFilter
 * @see #removeListener
 * 
 * @since 3.0 
 */
public void addFilter (int eventType, Listener listener) {
	checkDevice ();
	if (listener == null) error (SWT.ERROR_NULL_ARGUMENT);
	if (filterTable == null) filterTable = new EventTable ();
	filterTable.hook (eventType, listener);
}

void addLayoutDeferred (Composite comp) {
	if (layoutDeferred == null) layoutDeferred = new Composite [64];
	if (layoutDeferredCount == layoutDeferred.length) {
		Composite [] temp = new Composite [layoutDeferred.length + 64];
		System.arraycopy (layoutDeferred, 0, temp, 0, layoutDeferred.length);
		layoutDeferred = temp;
	}
	layoutDeferred[layoutDeferredCount++] = comp;
}

void addGdkEvent (long /*int*/ event) {
	if (gdkEvents == null) {
		int length = GROW_SIZE;
		gdkEvents = new long /*int*/ [length];
		gdkEventWidgets = new Widget [length];
		gdkEventCount = 0;
	}
	if (gdkEventCount == gdkEvents.length) {
		int length = gdkEventCount + GROW_SIZE;
		long /*int*/ [] newEvents = new long /*int*/ [length];
		System.arraycopy (gdkEvents, 0, newEvents, 0, gdkEventCount);
		gdkEvents = newEvents;
		Widget [] newWidgets = new Widget [length];
		System.arraycopy (gdkEventWidgets, 0, newWidgets, 0, gdkEventCount);
		gdkEventWidgets = newWidgets;
	}
	Widget widget = null;
	long /*int*/ handle = OS.gtk_get_event_widget (event);
	if (handle != 0) {
		do {
			widget = getWidget (handle);
		} while (widget == null && (handle = OS.gtk_widget_get_parent (handle)) != 0);
	}
	gdkEvents [gdkEventCount] = event;
	gdkEventWidgets [gdkEventCount] = widget;
	gdkEventCount++;
}

void addIdleProc() {
	synchronized (idleLock){
		this.idleNeeded = true;
		if (idleHandle == 0) {
			idleHandle = OS.g_idle_add (idleProc, 0);
		}
	}
}

/**
 * Adds the listener to the collection of listeners who will
 * be notified when an event of the given type occurs. The event
 * type is one of the event constants defined in class <code>SWT</code>.
 * When the event does occur in the display, the listener is notified by
 * sending it the <code>handleEvent()</code> message.
 *
 * @param eventType the type of event to listen for
 * @param listener the listener which should be notified when the event occurs
 *
 * @exception IllegalArgumentException <ul>
 *    <li>ERROR_NULL_ARGUMENT - if the listener is null</li>
 * </ul>
 * @exception SWTException <ul>
 *    <li>ERROR_THREAD_INVALID_ACCESS - if not called from the thread that created the receiver</li>
 *    <li>ERROR_DEVICE_DISPOSED - if the receiver has been disposed</li>
 * </ul>
 *
 * @see Listener
 * @see SWT
 * @see #removeListener
 * 
 * @since 2.0 
 */
public void addListener (int eventType, Listener listener) {
	checkDevice ();
	if (listener == null) error (SWT.ERROR_NULL_ARGUMENT);
	if (eventTable == null) eventTable = new EventTable ();
	eventTable.hook (eventType, listener);
}

long /*int*/ allChildrenProc (long /*int*/ widget, long /*int*/ recurse) {
	allChildren = OS.g_list_append (allChildren, widget);
	if (recurse != 0 && OS.GTK_IS_CONTAINER (widget)) {
		OS.gtk_container_forall (widget, allChildrenProc, recurse);
	}
	return 0;
}

void addMouseHoverTimeout (long /*int*/ handle) {
	if (mouseHoverId != 0) OS.g_source_remove (mouseHoverId);
	mouseHoverId = OS.g_timeout_add (400, mouseHoverProc, handle);
	mouseHoverHandle = handle;
}

void addPopup (Menu menu) {
	if (popups == null) popups = new Menu [4];
	int length = popups.length;
	for (int i=0; i<length; i++) {
		if (popups [i] == menu) return;
	}
	int index = 0;
	while (index < length) {
		if (popups [index] == null) break;
		index++;
	}
	if (index == length) {
		Menu [] newPopups = new Menu [length + 4];
		System.arraycopy (popups, 0, newPopups, 0, length);
		popups = newPopups;
	}
	popups [index] = menu;
}

void addSkinnableWidget (Widget widget) {
	if (skinCount >= skinList.length) {
		Widget[] newSkinWidgets = new Widget [skinList.length + GROW_SIZE];
		System.arraycopy (skinList, 0, newSkinWidgets, 0, skinList.length);
		skinList = newSkinWidgets;
	}
	skinList [skinCount++] = widget;
}

void addWidget (long /*int*/ handle, Widget widget) {
	if (handle == 0) return;
	if (freeSlot == -1) {
		int length = (freeSlot = indexTable.length) + GROW_SIZE;
		int[] newIndexTable = new int[length];
		Widget[] newWidgetTable = new Widget [length];
		System.arraycopy (indexTable, 0, newIndexTable, 0, freeSlot);
		System.arraycopy (widgetTable, 0, newWidgetTable, 0, freeSlot);
		for (int i = freeSlot; i < length - 1; i++) {
			newIndexTable[i] = i + 1;
		}
		newIndexTable[length - 1] = -1;
		indexTable = newIndexTable;
		widgetTable = newWidgetTable;
	}
	int index = freeSlot + 1;
	OS.g_object_set_qdata (handle, SWT_OBJECT_INDEX, index);
	int oldSlot = freeSlot;
	freeSlot = indexTable[oldSlot];
	indexTable [oldSlot] = -2;
	widgetTable [oldSlot] = widget;
}

/**
 * Causes the <code>run()</code> method of the runnable to
 * be invoked by the user-interface thread at the next 
 * reasonable opportunity. The caller of this method continues 
 * to run in parallel, and is not notified when the
 * runnable has completed.  Specifying <code>null</code> as the
 * runnable simply wakes the user-interface thread when run.
 * <p>
 * Note that at the time the runnable is invoked, widgets 
 * that have the receiver as their display may have been
 * disposed. Therefore, it is necessary to check for this
 * case inside the runnable before accessing the widget.
 * </p>
 *
 * @param runnable code to run on the user-interface thread or <code>null</code>
 *
 * @exception SWTException <ul>
 *    <li>ERROR_DEVICE_DISPOSED - if the receiver has been disposed</li>
 * </ul>
 * 
 * @see #syncExec
 */
public void asyncExec (Runnable runnable) {
	synchronized (Device.class) {
		if (isDisposed ()) error (SWT.ERROR_DEVICE_DISPOSED);
		synchronized (idleLock) {
			if (idleNeeded && idleHandle == 0) {
	 			//NOTE: calling unlocked function in OS
				idleHandle = OS._g_idle_add (idleProc, 0);
			}
		}
		synchronizer.asyncExec (runnable);
	}
}

/**
 * Causes the system hardware to emit a short sound
 * (if it supports this capability).
 * 
 * @exception SWTException <ul>
 *    <li>ERROR_THREAD_INVALID_ACCESS - if not called from the thread that created the receiver</li>
 *    <li>ERROR_DEVICE_DISPOSED - if the receiver has been disposed</li>
 * </ul>
 */
public void beep () {
	if (!isValidThread ()) error (SWT.ERROR_THREAD_INVALID_ACCESS);
	OS.gdk_beep();
	if (!OS.GDK_WINDOWING_X11 ()) {
		OS.gdk_flush ();
	} else {
		long /*int*/ xDisplay = OS.gdk_x11_display_get_xdisplay(OS.gdk_display_get_default());
		OS.XFlush (xDisplay);
	}
}

long /*int*/ cellDataProc (long /*int*/ tree_column, long /*int*/ cell, long /*int*/ tree_model, long /*int*/ iter, long /*int*/ data) {
	Widget widget = getWidget (data);
	if (widget == null) return 0;
	return widget.cellDataProc (tree_column, cell, tree_model, iter, data);
}

protected void checkDevice () {
	if (thread == null) error (SWT.ERROR_WIDGET_DISPOSED);
	if (thread != Thread.currentThread ()) error (SWT.ERROR_THREAD_INVALID_ACCESS);
	if (isDisposed ()) error (SWT.ERROR_DEVICE_DISPOSED);
}

static void checkDisplay (Thread thread, boolean multiple) {
	synchronized (Device.class) {
		for (int i=0; i<Displays.length; i++) {
			if (Displays [i] != null) {
				if (!multiple) SWT.error (SWT.ERROR_NOT_IMPLEMENTED, null, " [multiple displays]"); //$NON-NLS-1$
				if (Displays [i].thread == thread) SWT.error (SWT.ERROR_THREAD_INVALID_ACCESS);
			}
		}
	}
}

long /*int*/ checkIfEventProc (long /*int*/ display, long /*int*/ xEvent, long /*int*/ userData) {
	int type = OS.X_EVENT_TYPE (xEvent);
	switch (type) {
		case OS.VisibilityNotify:
			/*
			* As of GTK 2.17.11, obscured controls no longer send expose 
			* events. It is no longer necessary to track visiblity notify
			* events.
			*/
			if (OS.GTK_VERSION >= OS.VERSION (2, 17, 11)) return 0;
		case OS.Expose:
		case OS.GraphicsExpose:
			break;
		default:
			return 0;
	}
	long /*int*/ window = 0;
	if (OS.GTK_VERSION >= OS.VERSION (2, 24, 0)) {
		window = OS.gdk_x11_window_lookup_for_display(OS.gdk_display_get_default(), OS.X_EVENT_WINDOW (xEvent));
	} else {
	    window = OS.gdk_window_lookup (OS.X_EVENT_WINDOW (xEvent));
	}
	if (window == 0) return 0;
	if (flushWindow != 0) {
		if (flushAll) {
			long /*int*/ tempWindow = window;
			do {
				if (tempWindow == flushWindow) break;
			} while ((tempWindow = OS.gdk_window_get_parent (tempWindow)) != 0);
			if (tempWindow != flushWindow) return 0;
		} else {
			if (window != flushWindow) return 0;
		}
	}
	OS.memmove (exposeEvent, xEvent, XExposeEvent.sizeof);
	switch (type) {
		case OS.Expose:
		case OS.GraphicsExpose: {
			flushRect.x = exposeEvent.x;
			flushRect.y = exposeEvent.y;
			flushRect.width = exposeEvent.width;
			flushRect.height = exposeEvent.height;
			OS.gdk_window_invalidate_rect (window, flushRect, true);
			exposeEvent.type = -1;
			OS.memmove (xEvent, exposeEvent, XExposeEvent.sizeof);
			break;
		}
		case OS.VisibilityNotify: {
			OS.memmove (visibilityEvent, xEvent, XVisibilityEvent.sizeof);
			OS.gdk_window_get_user_data (window, flushData);
			long /*int*/ handle = flushData [0];
			Widget widget = handle != 0 ? getWidget (handle) : null;
			if (widget != null && widget instanceof Control) {
				Control control = (Control) widget;
				if (window == control.paintWindow ()) {
					if (visibilityEvent.state == OS.VisibilityFullyObscured) {
						control.state |= Widget.OBSCURED;
					} else {
						control.state &= ~Widget.OBSCURED;
					}
				}
			}
			break;
		}
	}
	return 0;
}

/**
 * Checks that this class can be subclassed.
 * <p>
 * IMPORTANT: See the comment in <code>Widget.checkSubclass()</code>.
 * </p>
 *
 * @exception SWTException <ul>
 *    <li>ERROR_INVALID_SUBCLASS - if this class is not an allowed subclass</li>
 * </ul>
 *
 * @see Widget#checkSubclass
 */
protected void checkSubclass () {
	if (!isValidClass (getClass ())) error (SWT.ERROR_INVALID_SUBCLASS);
}

void clearModal (Shell shell) {
	if (modalShells == null) return;
	int index = 0, length = modalShells.length;
	while (index < length) {
		if (modalShells [index] == shell) break;
		if (modalShells [index] == null) return;
		index++;
	}
	if (index == length) return;
	System.arraycopy (modalShells, index + 1, modalShells, index, --length - index);
	modalShells [length] = null;
	if (index == 0 && modalShells [0] == null) modalShells = null;
	Shell [] shells = getShells ();
	for (int i=0; i<shells.length; i++) shells [i].updateModal ();
}

/**
 * Requests that the connection between SWT and the underlying
 * operating system be closed.
 *
 * @exception SWTException <ul>
 *    <li>ERROR_THREAD_INVALID_ACCESS - if not called from the thread that created the receiver</li>
 *    <li>ERROR_DEVICE_DISPOSED - if the receiver has been disposed</li>
 * </ul>
 *
 * @see Device#dispose
 * 
 * @since 2.0
 */
public void close () {
	checkDevice ();
	Event event = new Event ();
	sendEvent (SWT.Close, event);
	if (event.doit) dispose ();
}

/**
 * Creates the device in the operating system.  If the device
 * does not have a handle, this method may do nothing depending
 * on the device.
 * <p>
 * This method is called before <code>init</code>.
 * </p>
 *
 * @param data the DeviceData which describes the receiver
 *
 * @see #init
 */
protected void create (DeviceData data) {
	checkSubclass ();
	checkDisplay(thread = Thread.currentThread (), false);
	createDisplay (data);
	register (this);
	if (Default == null) Default = this;
}

void createDisplay (DeviceData data) {
	if (OS.GLIB_VERSION < OS.VERSION(2, 32, 0)) {
		/* Required for g_main_context_wakeup */
		if (!OS.g_thread_supported()) {
			OS.g_thread_init(0);
		}
	}
	if (OS.GTK_VERSION < OS.VERSION(2, 24, 0)) {
	    OS.gtk_set_locale();
	}
	if (!OS.gtk_init_check (new long /*int*/ [] {0}, null)) {
		SWT.error (SWT.ERROR_NO_HANDLES, null, " [gtk_init_check() failed]"); //$NON-NLS-1$
	}
	if (OS.GDK_WINDOWING_X11 ()) xDisplay = OS.gdk_x11_get_default_xdisplay();
	int major = OS.gtk_major_version ();
	long /*int*/ ptr;
	if (major == GTK3_MAJOR) {
		ptr = OS.gtk_check_version (GTK3_MAJOR, GTK3_MINOR, GTK3_MICRO);
	} else {
		ptr = OS.gtk_check_version (GTK2_MAJOR, GTK2_MINOR, GTK2_MICRO);
	}
	if (ptr != 0) {
		int length = OS.strlen (ptr);
		byte [] buffer = new byte [length];
		OS.memmove (buffer, ptr, length);
		System.out.println ("***WARNING: " + new String (Converter.mbcsToWcs (null, buffer))); //$NON-NLS-1$
		System.out.println ("***WARNING: SWT requires GTK " + GTK2_MAJOR+ "." + GTK2_MINOR + "." + GTK2_MICRO); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		int minor = OS.gtk_minor_version (), micro = OS.gtk_micro_version ();
		System.out.println ("***WARNING: Detected: " + major + "." + minor + "." + micro); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
	}
	if (OS.GTK3) {
		fixed_type = OS.swt_fixed_get_type();
	}
	if (fixed_type == 0) {
		byte [] type_name = Converter.wcsToMbcs (null, "SwtFixed", true); //$NON-NLS-1$
		fixedClassInitCallback = new Callback (getClass (), "fixedClassInitProc", 2); //$NON-NLS-1$
		fixedClassInitProc = fixedClassInitCallback.getAddress ();
		if (fixedClassInitProc == 0) error (SWT.ERROR_NO_MORE_CALLBACKS);
		fixedMapCallback = new Callback (getClass (), "fixedMapProc", 1); //$NON-NLS-1$
		fixedMapProc = fixedMapCallback.getAddress ();
		if (fixedMapProc == 0) error (SWT.ERROR_NO_MORE_CALLBACKS);
		fixedSizeAllocateCallback = new Callback (getClass (), "fixedSizeAllocateProc", 2); //$NON-NLS-1$
		fixedSizeAllocateProc = fixedSizeAllocateCallback.getAddress ();
		if (fixedSizeAllocateProc == 0) error (SWT.ERROR_NO_MORE_CALLBACKS);
		long /*int*/ queryPtr = OS.g_malloc (GTypeQuery.sizeof);
		OS.g_type_query (OS.GTK_TYPE_FIXED(), queryPtr);
		GTypeQuery query = new GTypeQuery ();
		OS.memmove (query, queryPtr, GTypeQuery.sizeof);
		OS.g_free (queryPtr);
		GTypeInfo fixed_info = new GTypeInfo ();
		fixed_info.class_size = (short) query.class_size;
		fixed_info.class_init = fixedClassInitProc;
		fixed_info.instance_size = (short) query.instance_size;
		fixed_info_ptr = OS.g_malloc (GTypeInfo.sizeof);
		OS.memmove (fixed_info_ptr, fixed_info, GTypeInfo.sizeof);
		fixed_type = OS.g_type_register_static (OS.GTK_TYPE_FIXED (), type_name, fixed_info_ptr, 0);
	}
	if (rendererClassInitProc == 0) {
		rendererClassInitCallback = new Callback (getClass (), "rendererClassInitProc", 2); //$NON-NLS-1$
		rendererClassInitProc = rendererClassInitCallback.getAddress ();
		if (rendererClassInitProc == 0) error (SWT.ERROR_NO_MORE_CALLBACKS);
	}
	if (rendererRenderProc == 0) {
		rendererRenderCallback = new Callback (getClass (), "rendererRenderProc", OS.GTK3 ? 6 : 7); //$NON-NLS-1$
		rendererRenderProc = rendererRenderCallback.getAddress ();
		if (rendererRenderProc == 0) error (SWT.ERROR_NO_MORE_CALLBACKS);
	}
	if (OS.GTK3) {
		if (rendererGetPreferredWidthProc == 0) {
			rendererGetPreferredWidthCallback = new Callback (getClass (), "rendererGetPreferredWidthProc", 4); //$NON-NLS-1$
			rendererGetPreferredWidthProc = rendererGetPreferredWidthCallback.getAddress ();
			if (rendererGetPreferredWidthProc == 0) error (SWT.ERROR_NO_MORE_CALLBACKS);
		}
	} else {
		if (rendererGetSizeProc == 0) {
			rendererGetSizeCallback = new Callback (getClass (), "rendererGetSizeProc", 7); //$NON-NLS-1$
			rendererGetSizeProc = rendererGetSizeCallback.getAddress ();
			if (rendererGetSizeProc == 0) error (SWT.ERROR_NO_MORE_CALLBACKS);
		}
	}
	if (text_renderer_type == 0) {
		GTypeInfo renderer_info = new GTypeInfo ();
		renderer_info.class_size = (short) OS.GtkCellRendererTextClass_sizeof ();
		renderer_info.class_init = rendererClassInitProc;
		renderer_info.instance_size = (short) OS.GtkCellRendererText_sizeof ();
		text_renderer_info_ptr = OS.g_malloc (GTypeInfo.sizeof);
		OS.memmove (text_renderer_info_ptr, renderer_info, GTypeInfo.sizeof);
		byte [] type_name = Converter.wcsToMbcs (null, "SwtTextRenderer", true); //$NON-NLS-1$
		text_renderer_type = OS.g_type_register_static (OS.GTK_TYPE_CELL_RENDERER_TEXT (), type_name, text_renderer_info_ptr, 0);
	}
	if (pixbuf_renderer_type == 0) {
		GTypeInfo renderer_info = new GTypeInfo ();
		renderer_info.class_size = (short) OS.GtkCellRendererPixbufClass_sizeof ();
		renderer_info.class_init = rendererClassInitProc;
		renderer_info.instance_size = (short) OS.GtkCellRendererPixbuf_sizeof ();
		pixbuf_renderer_info_ptr = OS.g_malloc (GTypeInfo.sizeof);
		OS.memmove (pixbuf_renderer_info_ptr, renderer_info, GTypeInfo.sizeof);
		byte [] type_name = Converter.wcsToMbcs (null, "SwtPixbufRenderer", true); //$NON-NLS-1$
		pixbuf_renderer_type = OS.g_type_register_static (OS.GTK_TYPE_CELL_RENDERER_PIXBUF (), type_name, pixbuf_renderer_info_ptr, 0);
	}	
	if (toggle_renderer_type == 0) {
		GTypeInfo renderer_info = new GTypeInfo ();
		renderer_info.class_size = (short) OS.GtkCellRendererToggleClass_sizeof ();
		renderer_info.class_init = rendererClassInitProc;
		renderer_info.instance_size = (short) OS.GtkCellRendererToggle_sizeof ();
		toggle_renderer_info_ptr = OS.g_malloc (GTypeInfo.sizeof);
		OS.memmove (toggle_renderer_info_ptr, renderer_info, GTypeInfo.sizeof);
		byte [] type_name = Converter.wcsToMbcs (null, "SwtToggleRenderer", true); //$NON-NLS-1$
		toggle_renderer_type = OS.g_type_register_static (OS.GTK_TYPE_CELL_RENDERER_TOGGLE (), type_name, toggle_renderer_info_ptr, 0);
	}
	
	OS.gtk_widget_set_default_direction (OS.GTK_TEXT_DIR_LTR);
	byte [] buffer = Converter.wcsToMbcs (null, APP_NAME, true);
	OS.g_set_prgname (buffer);
	OS.gdk_set_program_class (buffer);
	if (!OS.GTK3) {
		byte [] flatStyle = Converter.wcsToMbcs (null, "style \"swt-flat\" { GtkToolbar::shadow-type = none } widget \"*.swt-toolbar-flat\" style : highest \"swt-flat\"", true); //$NON-NLS-1$
		OS.gtk_rc_parse_string (flatStyle);
	}

	/* Initialize the hidden shell */
	shellHandle = OS.gtk_window_new (OS.GTK_WINDOW_TOPLEVEL);
	if (shellHandle == 0) error (SWT.ERROR_NO_HANDLES);
	OS.gtk_widget_realize (shellHandle);

	/* Initialize the filter and event callback */
	eventCallback = new Callback (this, "eventProc", 2); //$NON-NLS-1$
	eventProc = eventCallback.getAddress ();
	if (eventProc == 0) error (SWT.ERROR_NO_MORE_CALLBACKS);
	OS.gdk_event_handler_set (eventProc, 0, 0);
	filterCallback = new Callback (this, "filterProc", 3); //$NON-NLS-1$
	filterProc = filterCallback.getAddress ();
	if (filterProc == 0) error (SWT.ERROR_NO_MORE_CALLBACKS);
	OS.gdk_window_add_filter  (0, filterProc, 0);

	if (OS.GDK_WINDOWING_X11 ()) {
		long /*int*/ xWindow;
		if (OS.GTK3) {
			xWindow = OS.gdk_x11_window_get_xid (OS.gtk_widget_get_window (shellHandle));
		} else if (OS.GTK_VERSION >= OS.VERSION(2, 14, 0)){
			xWindow = OS.gdk_x11_drawable_get_xid (OS.gtk_widget_get_window	(shellHandle));
		} else {
			xWindow = OS.gdk_x11_drawable_get_xid (OS.GTK_WIDGET_WINDOW (shellHandle));
		}
		byte[] atomName = Converter.wcsToMbcs (null, "SWT_Window_" + APP_NAME, true); //$NON-NLS-1$
		long /*int*/ atom = OS.XInternAtom (xDisplay, atomName, false);
		OS.XSetSelectionOwner (xDisplay, atom, xWindow, OS.CurrentTime);
		OS.XGetSelectionOwner (xDisplay, atom);
	}

	signalCallback = new Callback (this, "signalProc", 3); //$NON-NLS-1$
	signalProc = signalCallback.getAddress ();
	if (signalProc == 0) error (SWT.ERROR_NO_MORE_CALLBACKS);
	OS.gtk_widget_add_events (shellHandle, OS.GDK_PROPERTY_CHANGE_MASK);
	OS.g_signal_connect (shellHandle, OS.property_notify_event, signalProc, PROPERTY_NOTIFY);
}

Image createImage (String name) {
	byte[] buffer = Converter.wcsToMbcs (null, name, true);
	long /*int*/ pixbuf, icon_set = OS.gtk_icon_factory_lookup_default (buffer);
	if (OS.GTK3) {
		pixbuf = OS.gtk_icon_set_render_icon_pixbuf(icon_set, OS.gtk_widget_get_style_context(shellHandle), OS.GTK_ICON_SIZE_DIALOG);
	} else {
		long /*int*/ style = OS.gtk_widget_get_default_style ();
		pixbuf = OS.gtk_icon_set_render_icon (icon_set, style,
			OS.GTK_TEXT_DIR_NONE, OS.GTK_STATE_NORMAL, OS.GTK_ICON_SIZE_DIALOG, 0, 0);
	}
	if (pixbuf == 0) return null;
	int width = OS.gdk_pixbuf_get_width (pixbuf);
	int height = OS.gdk_pixbuf_get_height (pixbuf);
	int stride = OS.gdk_pixbuf_get_rowstride (pixbuf);
	boolean hasAlpha = OS.gdk_pixbuf_get_has_alpha (pixbuf);
	long /*int*/ pixels = OS.gdk_pixbuf_get_pixels (pixbuf);
	byte [] data = new byte [stride * height];
	OS.memmove (data, pixels, data.length);
	OS.g_object_unref (pixbuf);
	ImageData imageData = null;
	if (hasAlpha) {
		PaletteData palette = new PaletteData (0xFF000000, 0xFF0000, 0xFF00);
		imageData = new ImageData (width, height, 32, palette);
		byte [] alpha = new byte [stride * height];
		for (int y=0; y<height; y++) {
			for (int x=0; x<width; x++) {
				alpha [y*width+x] = data [y*stride+x*4+3];
				data [y*stride+x*4+3] = 0;
			}
		}
		imageData.setAlphas (0, 0, width * height, alpha, 0);
	} else {
		PaletteData palette = new PaletteData (0xFF0000, 0xFF00, 0xFF);
		imageData = new ImageData (width, height, 24, palette);
	}
	imageData.data = data;
	imageData.bytesPerLine = stride;
	return new Image (this, imageData);
}

static void deregister (Display display) {
	synchronized (Device.class) {
		for (int i=0; i<Displays.length; i++) {
			if (display == Displays [i]) Displays [i] = null;
		}
	}
}

/**
 * Destroys the device in the operating system and releases
 * the device's handle.  If the device does not have a handle,
 * this method may do nothing depending on the device.
 * <p>
 * This method is called after <code>release</code>.
 * </p>
 * @see Device#dispose
 * @see #release
 */
protected void destroy () {
	if (this == Default) Default = null;
	deregister (this);
	destroyDisplay ();
}

void destroyDisplay () {
}

long /*int*/ emissionProc (long /*int*/ ihint, long /*int*/ n_param_values, long /*int*/ param_values, long /*int*/ data) {
	if (OS.gtk_widget_get_toplevel (OS.g_value_peek_pointer(param_values)) == data) {
		OS.gtk_widget_set_direction (OS.g_value_peek_pointer(param_values), OS.GTK_TEXT_DIR_RTL);
	}
	return 1;
}

/**
 * Returns the display which the given thread is the
 * user-interface thread for, or null if the given thread
 * is not a user-interface thread for any display.  Specifying
 * <code>null</code> as the thread will return <code>null</code>
 * for the display. 
 *
 * @param thread the user-interface thread
 * @return the display for the given thread
 */
public static Display findDisplay (Thread thread) {
	synchronized (Device.class) {
		for (int i=0; i<Displays.length; i++) {
			Display display = Displays [i];
			if (display != null && display.thread == thread) {
				return display;
			}
		}
		return null;
	}
}

/**
 * Causes the <code>run()</code> method of the runnable to
 * be invoked by the user-interface thread just before the
 * receiver is disposed.  Specifying a <code>null</code> runnable
 * is ignored.
 *
 * @param runnable code to run at dispose time.
 * 
 * @exception SWTException <ul>
 *    <li>ERROR_THREAD_INVALID_ACCESS - if not called from the thread that created the receiver</li>
 *    <li>ERROR_DEVICE_DISPOSED - if the receiver has been disposed</li>
 * </ul>
 */
public void disposeExec (Runnable runnable) {
	checkDevice ();
	if (disposeList == null) disposeList = new Runnable [4];
	for (int i=0; i<disposeList.length; i++) {
		if (disposeList [i] == null) {
			disposeList [i] = runnable;
			return;
		}
	}
	Runnable [] newDisposeList = new Runnable [disposeList.length + 4];
	System.arraycopy (disposeList, 0, newDisposeList, 0, disposeList.length);
	newDisposeList [disposeList.length] = runnable;
	disposeList = newDisposeList;
}

/**
 * Does whatever display specific cleanup is required, and then
 * uses the code in <code>SWTError.error</code> to handle the error.
 *
 * @param code the descriptive error code
 *
 * @see SWTError#error
 */
void error (int code) {
	SWT.error (code);
}

long /*int*/ eventProc (long /*int*/ event, long /*int*/ data) {
	/*
	* Use gdk_event_get_time() rather than event.time or
	* gtk_get_current_event_time().  If the event does not
	* have a time stamp, then the field will contain garbage.
	* Note that calling gtk_get_current_event_time() from
	* outside of gtk_main_do_event() seems to always
	* return zero.
	*/
	int time = OS.gdk_event_get_time (event);
	if (time != 0) lastEventTime = time;

	int eventType = OS.GDK_EVENT_TYPE (event);
	switch (eventType) {
		case OS.GDK_BUTTON_PRESS:
		case OS.GDK_KEY_PRESS:
			lastUserEventTime = time;
	}
	boolean dispatch = true;
	if (dispatchEvents != null) {
		dispatch = false;
		for (int i = 0; i < dispatchEvents.length; i++) {
			if (eventType == dispatchEvents [i]) {
				dispatch = true;
				break;
			}
		}
	}
	if (!dispatch) {
		addGdkEvent (OS.gdk_event_copy (event));
		return 0;
	}
	dispatch = true;
	if (tracker != null) {
		dispatch = tracker.processEvent (event);
	}
	if (dispatch) OS.gtk_main_do_event (event);
	if (dispatchEvents == null) putGdkEvents ();
	return 0;
}

/**
 * Given the operating system handle for a widget, returns
 * the instance of the <code>Widget</code> subclass which
 * represents it in the currently running application, if
 * such exists, or null if no matching widget can be found.
 * <p>
 * <b>IMPORTANT:</b> This method should not be called from
 * application code. The arguments are platform-specific.
 * </p>
 *
 * @param handle the handle for the widget
 * @return the SWT widget that the handle represents
 *
 * @exception SWTException <ul>
 *    <li>ERROR_THREAD_INVALID_ACCESS - if not called from the thread that created the receiver</li>
 *    <li>ERROR_DEVICE_DISPOSED - if the receiver has been disposed</li>
 * </ul>
 * 
 * @noreference This method is not intended to be referenced by clients.
 */
public Widget findWidget (long /*int*/ handle) {
	checkDevice ();
	return getWidget (handle);
}

/**
 * Given the operating system handle for a widget,
 * and widget-specific id, returns the instance of
 * the <code>Widget</code> subclass which represents
 * the handle/id pair in the currently running application,
 * if such exists, or null if no matching widget can be found.
 * <p>
 * <b>IMPORTANT:</b> This method should not be called from
 * application code. The arguments are platform-specific.
 * </p>
 *
 * @param handle the handle for the widget
 * @param id the id for the subwidget (usually an item)
 * @return the SWT widget that the handle/id pair represents
 *
 * @exception SWTException <ul>
 *    <li>ERROR_THREAD_INVALID_ACCESS - if not called from the thread that created the receiver</li>
 *    <li>ERROR_DEVICE_DISPOSED - if the receiver has been disposed</li>
 * </ul>
 * 
 * @noreference This method is not intended to be referenced by clients.
 * 
 * @since 3.1
 */
public Widget findWidget (long /*int*/ handle, long /*int*/ id) {
	checkDevice ();
	return null;
}

/**
 * Given a widget and a widget-specific id, returns the
 * instance of the <code>Widget</code> subclass which represents
 * the widget/id pair in the currently running application,
 * if such exists, or null if no matching widget can be found.
 *
 * @param widget the widget
 * @param id the id for the subwidget (usually an item)
 * @return the SWT subwidget (usually an item) that the widget/id pair represents
 *
 * @exception SWTException <ul>
 *    <li>ERROR_THREAD_INVALID_ACCESS - if not called from the thread that created the receiver</li>
 *    <li>ERROR_DEVICE_DISPOSED - if the receiver has been disposed</li>
 * </ul>
 * 
 * @noreference This method is not intended to be referenced by clients.
 * 
 * @since 3.3
 */
public Widget findWidget (Widget widget, long /*int*/ id) {
	checkDevice ();
	return null;
}

static long /*int*/ fixedClassInitProc (long /*int*/ g_class, long /*int*/ class_data) {
	GtkWidgetClass klass = new GtkWidgetClass ();
	OS.memmove (klass, g_class);
	klass.map = fixedMapProc;
	oldFixedSizeAllocateProc = klass.size_allocate;
	klass.size_allocate = fixedSizeAllocateProc;
	OS.memmove (g_class, klass);
	return 0;
}

static long /*int*/ fixedMapProc (long /*int*/ handle) {
	Display display = getCurrent ();
	Widget widget = display.getWidget (handle);
	if (widget != null) return widget.fixedMapProc (handle);
	return 0;
}

static long /*int*/ fixedSizeAllocateProc (long /*int*/ handle, long /*int*/ allocation) {
	Display display = getCurrent ();
	Widget widget = display.getWidget (handle);
	if (widget != null) return widget.fixedSizeAllocateProc (handle, allocation);
	return OS.Call (oldFixedSizeAllocateProc, handle, allocation);
}

static long /*int*/ rendererClassInitProc (long /*int*/ g_class, long /*int*/ class_data) {
	GtkCellRendererClass klass = new GtkCellRendererClass ();
	OS.memmove (klass, g_class);
	klass.render = rendererRenderProc;
	if (OS.GTK3) {
		klass.get_preferred_width = rendererGetPreferredWidthProc;
	} else {
		klass.get_size = rendererGetSizeProc;
	}
	OS.memmove (g_class, klass);
	return 0;
}

static long /*int*/ rendererGetPreferredWidthProc (long /*int*/ cell, long /*int*/ handle, long /*int*/ minimun_size, long /*int*/ natural_size) {
	Display display = getCurrent ();
	Widget widget = display.getWidget (handle);
	if (widget != null) return widget.rendererGetPreferredWidthProc (cell, handle, minimun_size, natural_size);
	return 0;
}

static long /*int*/ rendererGetSizeProc (long /*int*/ cell, long /*int*/ handle, long /*int*/ cell_area, long /*int*/ x_offset, long /*int*/ y_offset, long /*int*/ width, long /*int*/ height) {
	Display display = getCurrent ();
	Widget widget = display.getWidget (handle);
	if (widget != null) return widget.rendererGetSizeProc (cell, handle, cell_area, x_offset, y_offset, width, height);
	return 0;
}

static long /*int*/ rendererRenderProc (long /*int*/ cell, long /*int*/ cr, long /*int*/ handle, long /*int*/ background_area, long /*int*/ cell_area, long /*int*/ flags) {
	Display display = getCurrent ();
	Widget widget = display.getWidget (handle);
	if (widget != null) return widget.rendererRenderProc (cell, cr, handle, background_area, cell_area, flags);
	return 0;
}

static long /*int*/ rendererRenderProc (long /*int*/ cell, long /*int*/ window, long /*int*/ handle, long /*int*/ background_area, long /*int*/ cell_area, long /*int*/ expose_area, long /*int*/ flags) {
	Display display = getCurrent ();
	Widget widget = display.getWidget (handle);
	if (widget != null) return widget.rendererRenderProc (cell, window, handle, background_area, cell_area, expose_area, flags);
	return 0;
}

void flushExposes (long /*int*/ window, boolean all) {
	OS.gdk_flush ();
	OS.gdk_flush ();
	if (OS.GDK_WINDOWING_X11 ()) {
		this.flushWindow = window;
		this.flushAll = all;
		long /*int*/ xDisplay = OS.gdk_x11_display_get_xdisplay(OS.gdk_display_get_default());
		long /*int*/ xEvent = OS.g_malloc (XEvent.sizeof);
		OS.XCheckIfEvent (xDisplay, xEvent, checkIfEventProc, 0);
		OS.g_free (xEvent);
		this.flushWindow = 0;
	}
}

/**
 * Returns the currently active <code>Shell</code>, or null
 * if no shell belonging to the currently running application
 * is active.
 *
 * @return the active shell or null
 *
 * @exception SWTException <ul>
 *    <li>ERROR_THREAD_INVALID_ACCESS - if not called from the thread that created the receiver</li>
 *    <li>ERROR_DEVICE_DISPOSED - if the receiver has been disposed</li>
 * </ul>
 */
public Shell getActiveShell () {
	checkDevice ();
	return activeShell;
}

/**
 * Returns a rectangle describing the receiver's size and location. Note that
 * on multi-monitor systems the origin can be negative.
 *
 * @return the bounding rectangle
 *
 * @exception SWTException <ul>
 *    <li>ERROR_THREAD_INVALID_ACCESS - if not called from the thread that created the receiver</li>
 *    <li>ERROR_DEVICE_DISPOSED - if the receiver has been disposed</li>
 * </ul>
 */
public Rectangle getBounds () {
	checkDevice ();
	return new Rectangle (0, 0, OS.gdk_screen_width (), OS.gdk_screen_height ());
}

/**
 * Returns the display which the currently running thread is
 * the user-interface thread for, or null if the currently
 * running thread is not a user-interface thread for any display.
 *
 * @return the current display
 */
public static Display getCurrent () {
	return findDisplay (Thread.currentThread ());
}

int getCaretBlinkTime () {
//	checkDevice ();
	long /*int*/ settings = OS.gtk_settings_get_default ();
	if (settings == 0) return 500;
	int [] buffer = new int [1];
	OS.g_object_get (settings, OS.gtk_cursor_blink, buffer, 0);
	if (buffer [0] == 0) return 0;
	OS.g_object_get (settings, OS.gtk_cursor_blink_time, buffer, 0);
	if (buffer [0] == 0) return 500;
	/*
	* By experimentation, GTK application don't use the whole
	* blink cycle time.  Instead, they divide up the time, using
	* an effective blink rate of about 1/2 the total time.
	*/
	return buffer [0] / 2;
}

/**
 * Returns the control which the on-screen pointer is currently
 * over top of, or null if it is not currently over one of the
 * controls built by the currently running application.
 *
 * @return the control under the cursor or <code>null</code>
 *
 * @exception SWTException <ul>
 *    <li>ERROR_THREAD_INVALID_ACCESS - if not called from the thread that created the receiver</li>
 *    <li>ERROR_DEVICE_DISPOSED - if the receiver has been disposed</li>
 * </ul>
 */
public Control getCursorControl () {
	checkDevice();
	int[] x = new int[1], y = new int[1];
	long /*int*/ handle = 0;
	long /*int*/ [] user_data = new long /*int*/ [1];
	long /*int*/ window = gdk_device_get_window_at_position (x,y);
	if (window != 0) {
		OS.gdk_window_get_user_data (window, user_data);
		handle = user_data [0];
	} else {
		/*
		* Feature in GTK. gdk_window_at_pointer() will not return a window 
		* if the pointer is over a foreign embedded window. The fix is to use
		* XQueryPointer to find the containing GDK window.
		*/
		if (!OS.GDK_WINDOWING_X11 ()) return null;
		OS.gdk_error_trap_push ();
		int[] unusedInt = new int[1];
		long /*int*/[] unusedPtr = new long /*int*/[1], buffer = new long /*int*/[1];
		long /*int*/ xWindow, xParent = OS.XDefaultRootWindow (xDisplay);
		do {
			if (OS.XQueryPointer (xDisplay, xParent, unusedPtr, buffer, unusedInt, unusedInt, unusedInt, unusedInt, unusedInt) == 0) {
				handle = 0;
				break;
			}
			if ((xWindow = buffer [0]) != 0) {
				xParent = xWindow;
				long /*int*/ gdkWindow = 0;
				if (OS.GTK_VERSION >= OS.VERSION (2, 24, 0)) {
					gdkWindow = OS.gdk_x11_window_lookup_for_display(OS.gdk_display_get_default(), xWindow);
				} else {
					gdkWindow = OS.gdk_window_lookup (xWindow);
				}
				if (gdkWindow != 0)	{
					OS.gdk_window_get_user_data (gdkWindow, user_data);
					if (user_data[0] != 0) handle = user_data[0];	
				}
			}
		} while (xWindow != 0);
		OS.gdk_error_trap_pop ();
	}
	if (handle == 0) return null;
	do {
		Widget widget = getWidget (handle);
		if (widget != null && widget instanceof Control) {
			Control control = (Control) widget;
			if (control.isEnabled ()) return control;
		}
	} while ((handle = OS.gtk_widget_get_parent (handle)) != 0);
	return null;
}

static GtkBorder getEntryInnerBorder (long /*int*/ handle) {
    GtkBorder gtkBorder = new GtkBorder();
    if (OS.GTK_VERSION >= OS.VERSION (2, 10, 0)) {
	    long /*int*/ border = OS.gtk_entry_get_inner_border (handle);
	    if (border != 0) {
	    	OS.memmove (gtkBorder, border, GtkBorder.sizeof);
	    	return gtkBorder;
	    }
	    long /*int*/ []  borderPtr = new long /*int*/ [1];
	    OS.gtk_widget_style_get (handle, OS.inner_border, borderPtr,0);
	    if (borderPtr[0] != 0) {
	        OS.memmove (gtkBorder, borderPtr[0], GtkBorder.sizeof);
	        OS.gtk_border_free(borderPtr[0]);
	        return gtkBorder;
	    }
    }
    gtkBorder.left = INNER_BORDER;
    gtkBorder.top = INNER_BORDER;
    gtkBorder.right = INNER_BORDER;
    gtkBorder.bottom = INNER_BORDER;
    return gtkBorder;
}

boolean filterEvent (Event event) {
	if (filterTable != null) filterTable.sendEvent (event);
	return false;
}

boolean filters (int eventType) {
	if (filterTable == null) return false;
	return filterTable.hooks (eventType);
}

long /*int*/ filterProc (long /*int*/ xEvent, long /*int*/ gdkEvent, long /*int*/ data) {
	Widget widget = getWidget (data);
	if (widget == null) return 0;
	return widget.filterProc (xEvent, gdkEvent, data);
}

/**
 * Returns the location of the on-screen pointer relative
 * to the top left corner of the screen.
 *
 * @return the cursor location
 *
 * @exception SWTException <ul>
 *    <li>ERROR_THREAD_INVALID_ACCESS - if not called from the thread that created the receiver</li>
 *    <li>ERROR_DEVICE_DISPOSED - if the receiver has been disposed</li>
 * </ul>
 */
public Point getCursorLocation () {
	checkDevice ();
	int [] x = new int [1], y = new int [1];
	gdk_window_get_device_position (0, x, y, null);
	return new Point (x [0], y [0]);
}

/**
 * Returns an array containing the recommended cursor sizes.
 *
 * @return the array of cursor sizes
 *
 * @exception SWTException <ul>
 *    <li>ERROR_THREAD_INVALID_ACCESS - if not called from the thread that created the receiver</li>
 *    <li>ERROR_DEVICE_DISPOSED - if the receiver has been disposed</li>
 * </ul>
 * 
 * @since 3.0
 */
public Point [] getCursorSizes () {
	checkDevice ();
	return new Point [] {new Point (16, 16), new Point (32, 32)}; 
}

/**
 * Returns the application defined property of the receiver
 * with the specified name, or null if it has not been set.
 * <p>
 * Applications may have associated arbitrary objects with the
 * receiver in this fashion. If the objects stored in the
 * properties need to be notified when the display is disposed
 * of, it is the application's responsibility to provide a
 * <code>disposeExec()</code> handler which does so.
 * </p>
 *
 * @param key the name of the property
 * @return the value of the property or null if it has not been set
 *
 * @exception IllegalArgumentException <ul>
 *    <li>ERROR_NULL_ARGUMENT - if the key is null</li>
 * </ul>
 * @exception SWTException <ul>
 *    <li>ERROR_THREAD_INVALID_ACCESS - if not called from the thread that created the receiver</li>
 *    <li>ERROR_DEVICE_DISPOSED - if the receiver has been disposed</li>
 * </ul>
 *
 * @see #setData(String, Object)
 * @see #disposeExec(Runnable)
 */
public Object getData (String key) {
	checkDevice ();
	if (key == null) error (SWT.ERROR_NULL_ARGUMENT);
	if (key.equals (DISPATCH_EVENT_KEY)) {
		return dispatchEvents;
	}
	if (key.equals (GET_MODAL_DIALOG)) {
		return modalDialog;
	}
	if (key.equals (GET_DIRECTION_PROC_KEY)) {
		return new LONG (setDirectionProc);
	}
	if (key.equals (GET_EMISSION_PROC_KEY)) {
		return new LONG (emissionProc);
	}
	if (keys == null) return null;
	for (int i=0; i<keys.length; i++) {
		if (keys [i].equals (key)) return values [i];
	}
	return null;
}

/**
 * Returns the application defined, display specific data
 * associated with the receiver, or null if it has not been
 * set. The <em>display specific data</em> is a single,
 * unnamed field that is stored with every display. 
 * <p>
 * Applications may put arbitrary objects in this field. If
 * the object stored in the display specific data needs to
 * be notified when the display is disposed of, it is the
 * application's responsibility to provide a
 * <code>disposeExec()</code> handler which does so.
 * </p>
 *
 * @return the display specific data
 *
 * @exception SWTException <ul>
 *    <li>ERROR_THREAD_INVALID_ACCESS - if not called from the thread that created the receiver</li>
 *    <li>ERROR_DEVICE_DISPOSED - if the receiver has been disposed</li>
 * </ul>
 *
 * @see #setData(Object)
 * @see #disposeExec(Runnable)
 */
public Object getData () {
	checkDevice ();
	return data;
}

long /*int*/ gtk_fixed_get_type () {
	return fixed_type;
}

long /*int*/ gtk_cell_renderer_text_get_type () {
	return text_renderer_type;
}

long /*int*/ gtk_cell_renderer_pixbuf_get_type () {
	return pixbuf_renderer_type;
}

long /*int*/ gtk_cell_renderer_toggle_get_type () {
	return toggle_renderer_type;
}

/**
 * Returns the default display. One is created (making the
 * thread that invokes this method its user-interface thread)
 * if it did not already exist.
 *
 * @return the default display
 */
public static Display getDefault () {
	synchronized (Device.class) {
		if (Default == null) Default = new Display ();
		return Default;
	}
}

static boolean isValidClass (Class clazz) {
	String name = clazz.getName ();
	int index = name.lastIndexOf ('.');
	return name.substring (0, index + 1).equals (PACKAGE_PREFIX);
}

/**
 * Returns the single instance of the application menu bar, or
 * <code>null</code> if there is no application menu bar for the platform.
 *
 * @return the application menu bar, or <code>null</code>
 * 
 * @exception SWTException <ul>
 *    <li>ERROR_DEVICE_DISPOSED - if the receiver has been disposed</li>
 * </ul>
 *
 * @since 3.7
 */
public Menu getMenuBar () {
	checkDevice ();
	return null;
}

/**
 * Returns the button dismissal alignment, one of <code>LEFT</code> or <code>RIGHT</code>.
 * The button dismissal alignment is the ordering that should be used when positioning the
 * default dismissal button for a dialog.  For example, in a dialog that contains an OK and
 * CANCEL button, on platforms where the button dismissal alignment is <code>LEFT</code>, the
 * button ordering should be OK/CANCEL.  When button dismissal alignment is <code>RIGHT</code>,
 * the button ordering should be CANCEL/OK.
 *
 * @return the button dismissal order
 *
 * @exception SWTException <ul>
 *    <li>ERROR_THREAD_INVALID_ACCESS - if not called from the thread that created the receiver</li>
 *    <li>ERROR_DEVICE_DISPOSED - if the receiver has been disposed</li>
 * </ul>
 * 
 * @since 2.1
 */
public int getDismissalAlignment () {
	checkDevice ();
	int [] buffer = new int [1];
	long /*int*/ settings = OS.gtk_settings_get_default ();
	OS.g_object_get (settings, OS.gtk_alternative_button_order, buffer, 0);
	return buffer [0] == 1 ? SWT.LEFT : SWT.RIGHT;
}

/**
 * Returns the longest duration, in milliseconds, between
 * two mouse button clicks that will be considered a
 * <em>double click</em> by the underlying operating system.
 *
 * @return the double click time
 *
 * @exception SWTException <ul>
 *    <li>ERROR_THREAD_INVALID_ACCESS - if not called from the thread that created the receiver</li>
 *    <li>ERROR_DEVICE_DISPOSED - if the receiver has been disposed</li>
 * </ul>
 */
public int getDoubleClickTime () {
	checkDevice ();
	long /*int*/ settings = OS.gtk_settings_get_default ();
	int [] buffer = new int [1];
	OS.g_object_get (settings, OS.gtk_double_click_time, buffer, 0);
	return buffer [0];
}

/**
 * Returns the control which currently has keyboard focus,
 * or null if keyboard events are not currently going to
 * any of the controls built by the currently running
 * application.
 *
 * @return the focus control or <code>null</code>
 *
 * @exception SWTException <ul>
 *    <li>ERROR_THREAD_INVALID_ACCESS - if not called from the thread that created the receiver</li>
 *    <li>ERROR_DEVICE_DISPOSED - if the receiver has been disposed</li>
 * </ul>
 */
public Control getFocusControl () {
	checkDevice ();
	if (focusControl != null && !focusControl.isDisposed ()) {
		return focusControl;
	}
	if (activeShell == null) return null;
	long /*int*/ shellHandle = activeShell.shellHandle;
	long /*int*/ handle = OS.gtk_window_get_focus (shellHandle);
	if (handle == 0) return null;
	do {
		Widget widget = getWidget (handle);
		if (widget != null && widget instanceof Control) {
			Control control = (Control) widget;
			return control.isEnabled () ? control : null;
		}
	} while ((handle = OS.gtk_widget_get_parent (handle)) != 0);
	return null;
}

/**
 * Returns true when the high contrast mode is enabled.
 * Otherwise, false is returned.
 * <p>
 * Note: This operation is a hint and is not supported on
 * platforms that do not have this concept.
 * </p>
 *
 * @return the high contrast mode
 *
 * @exception SWTException <ul>
 *    <li>ERROR_THREAD_INVALID_ACCESS - if not called from the thread that created the receiver</li>
 *    <li>ERROR_DEVICE_DISPOSED - if the receiver has been disposed</li>
 * </ul>
 * 
 * @since 3.0
 */
public boolean getHighContrast () {
	checkDevice ();
	return false;
}

public int getDepth () {
	checkDevice ();
	if (OS.GTK_VERSION >= OS.VERSION(2, 22, 0)) {
		return OS.gdk_visual_get_depth(OS.gdk_visual_get_system());
	} else {
		GdkVisual visual = new GdkVisual ();
		OS.memmove (visual, OS.gdk_visual_get_system());
		return visual.depth;
	}
}

/**
 * Returns the maximum allowed depth of icons on this display, in bits per pixel.
 * On some platforms, this may be different than the actual depth of the display.
 *
 * @return the maximum icon depth
 *
 * @exception SWTException <ul>
 *    <li>ERROR_THREAD_INVALID_ACCESS - if not called from the thread that created the receiver</li>
 *    <li>ERROR_DEVICE_DISPOSED - if the receiver has been disposed</li>
 * </ul>
 * 
 * @see Device#getDepth
 */
public int getIconDepth () {
	checkDevice ();
	return getDepth ();
}

/**
 * Returns an array containing the recommended icon sizes.
 *
 * @return the array of icon sizes
 *
 * @exception SWTException <ul>
 *    <li>ERROR_THREAD_INVALID_ACCESS - if not called from the thread that created the receiver</li>
 *    <li>ERROR_DEVICE_DISPOSED - if the receiver has been disposed</li>
 * </ul>
 * 
 * @see Decorations#setImages(Image[])
 * 
 * @since 3.0
 */
public Point [] getIconSizes () {
	checkDevice ();
	return new Point [] {new Point (16, 16), new Point (32, 32)}; 
}

int getLastEventTime () {
	return lastEventTime;
}

int getMessageCount () {
	return synchronizer.getMessageCount ();
}

Dialog getModalDialog () {
	return modalDialog;
}

/**
 * Returns the work area, an EWMH property to store the size
 * and position of the screen not covered by dock and panel
 * windows.  See http://freedesktop.org/Standards/wm-spec.
 */
Rectangle getWorkArea() {
	byte[] name = Converter.wcsToMbcs (null, "_NET_WORKAREA", true); //$NON-NLS-1$
	long /*int*/ atom = OS.gdk_atom_intern (name, true);
	if (atom == OS.GDK_NONE) return null;
	long /*int*/[] actualType = new long /*int*/[1];
	int[] actualFormat = new int[1];
	int[] actualLength = new int[1];
	long /*int*/[] data = new long /*int*/[1];
	if (!OS.gdk_property_get (OS.gdk_get_default_root_window(), atom, OS.GDK_NONE, 0, 16, 0, actualType, actualFormat, actualLength, data)) {
		return null;
	}
	Rectangle result = null;
	if (data [0] != 0) {
		if (actualLength [0] == 16) {
			int values [] = new int [4];
			OS.memmove (values, data[0], 16);
			result = new Rectangle (values [0],values [1],values [2],values [3]);
		} else if (actualLength [0] == 32) {
			long values [] = new long [4];
			OS.memmove (values, data[0], 32);
			result = new Rectangle ((int)values [0],(int)values [1],(int)values [2],(int)values [3]);			
		}
		OS.g_free (data [0]);
	}
	return result;
}

/**
 * Returns an array of monitors attached to the device.
 * 
 * @return the array of monitors
 * 
 * @since 3.0
 */
public Monitor [] getMonitors () {
	checkDevice ();
	Monitor [] monitors = null;
	Rectangle workArea = getWorkArea();
	long /*int*/ screen = OS.gdk_screen_get_default ();
	if (screen != 0) {
		int monitorCount = OS.gdk_screen_get_n_monitors (screen);
		if (monitorCount > 0) {
			monitors = new Monitor [monitorCount];
			GdkRectangle dest = new GdkRectangle ();
			for (int i = 0; i < monitorCount; i++) {
				OS.gdk_screen_get_monitor_geometry (screen, i, dest);
				Monitor monitor = new Monitor ();
				monitor.handle = i;
				monitor.x = dest.x;
				monitor.y = dest.y;
				monitor.width = dest.width;
				monitor.height = dest.height;
				if (i == 0 && workArea != null) {
					monitor.clientX = workArea.x;
					monitor.clientY = workArea.y;
					monitor.clientWidth = workArea.width;
					monitor.clientHeight = workArea.height;
				} else {
					monitor.clientX = monitor.x;
					monitor.clientY = monitor.y;
					monitor.clientWidth = monitor.width;
					monitor.clientHeight = monitor.height;
				}
				monitors [i] = monitor;
			}
		}
	}
	if (monitors == null) {
		/* No multimonitor support detected, default to one monitor */
		Monitor monitor = new Monitor ();
		Rectangle bounds = getBounds ();
		monitor.x = bounds.x;
		monitor.y = bounds.y;
		monitor.width = bounds.width;
		monitor.height = bounds.height;
		if (workArea != null) {
			monitor.clientX = workArea.x;
			monitor.clientY = workArea.y;
			monitor.clientWidth = workArea.width;
			monitor.clientHeight = workArea.height;
		} else {
			monitor.clientX = monitor.x;
			monitor.clientY = monitor.y;
			monitor.clientWidth = monitor.width;
			monitor.clientHeight = monitor.height;
		}
		monitors = new Monitor [] { monitor };			
	}
	return monitors;
}

/**
 * Returns the primary monitor for that device.
 * 
 * @return the primary monitor
 * 
 * @since 3.0
 */
public Monitor getPrimaryMonitor () {
	checkDevice ();
	Monitor [] monitors = getMonitors ();
	return monitors [0];
}

/**
 * Returns a (possibly empty) array containing all shells which have
 * not been disposed and have the receiver as their display.
 *
 * @return the receiver's shells
 *
 * @exception SWTException <ul>
 *    <li>ERROR_THREAD_INVALID_ACCESS - if not called from the thread that created the receiver</li>
 *    <li>ERROR_DEVICE_DISPOSED - if the receiver has been disposed</li>
 * </ul>
 */
public Shell [] getShells () {
	checkDevice ();
	int index = 0;
	Shell [] result = new Shell [16];
	for (int i = 0; i < widgetTable.length; i++) {
		Widget widget = widgetTable [i];
		if (widget != null && widget instanceof Shell) {
			int j = 0;
			while (j < index) {
				if (result [j] == widget) break;
				j++;
			}
			if (j == index) {
				if (index == result.length) {
					Shell [] newResult = new Shell [index + 16];
					System.arraycopy (result, 0, newResult, 0, index);
					result = newResult;
				}
				result [index++] = (Shell) widget;	
			}
		}
	}
	if (index == result.length) return result;
	Shell [] newResult = new Shell [index];
	System.arraycopy (result, 0, newResult, 0, index);
	return newResult;
}

/**
 * Gets the synchronizer used by the display.
 *
 * @return the receiver's synchronizer
 * 
 * @exception SWTException <ul>
 *    <li>ERROR_THREAD_INVALID_ACCESS - if not called from the thread that created the receiver</li>
 *    <li>ERROR_DEVICE_DISPOSED - if the receiver has been disposed</li>
 * </ul>
 * 
 * @since 3.4
 */
public Synchronizer getSynchronizer () {
	checkDevice ();
	return synchronizer;
}

/**
 * Returns the thread that has invoked <code>syncExec</code>
 * or null if no such runnable is currently being invoked by
 * the user-interface thread.
 * <p>
 * Note: If a runnable invoked by asyncExec is currently
 * running, this method will return null.
 * </p>
 *
 * @return the receiver's sync-interface thread
 * 
 * @exception SWTException <ul>
 *    <li>ERROR_DEVICE_DISPOSED - if the receiver has been disposed</li>
 * </ul>
 */
public Thread getSyncThread () {
	synchronized (Device.class) {
		if (isDisposed ()) error (SWT.ERROR_DEVICE_DISPOSED);
		return synchronizer.syncThread;
	}
}

/**
 * Returns the matching standard color for the given
 * constant, which should be one of the color constants
 * specified in class <code>SWT</code>. Any value other
 * than one of the SWT color constants which is passed
 * in will result in the color black. This color should
 * not be free'd because it was allocated by the system,
 * not the application.
 *
 * @param id the color constant
 * @return the matching color
 *
 * @exception SWTException <ul>
 *    <li>ERROR_THREAD_INVALID_ACCESS - if not called from the thread that created the receiver</li>
 *    <li>ERROR_DEVICE_DISPOSED - if the receiver has been disposed</li>
 * </ul>
 *
 * @see SWT
 */
public Color getSystemColor (int id) {
	checkDevice ();
	GdkColor gdkColor = null;
	switch (id) {
		case SWT.COLOR_LINK_FOREGROUND: 					gdkColor = COLOR_LINK_FOREGROUND; break;
		case SWT.COLOR_INFO_FOREGROUND: 					gdkColor = COLOR_INFO_FOREGROUND; break;
		case SWT.COLOR_INFO_BACKGROUND: 					gdkColor = COLOR_INFO_BACKGROUND; break;
		case SWT.COLOR_TITLE_FOREGROUND:					gdkColor = COLOR_TITLE_FOREGROUND; break;
		case SWT.COLOR_TITLE_BACKGROUND:					gdkColor = COLOR_TITLE_BACKGROUND; break;
		case SWT.COLOR_TITLE_BACKGROUND_GRADIENT:			gdkColor = COLOR_TITLE_BACKGROUND_GRADIENT; break;
		case SWT.COLOR_TITLE_INACTIVE_FOREGROUND:			gdkColor = COLOR_TITLE_INACTIVE_FOREGROUND; break;
		case SWT.COLOR_TITLE_INACTIVE_BACKGROUND:			gdkColor = COLOR_TITLE_INACTIVE_BACKGROUND; break;
		case SWT.COLOR_TITLE_INACTIVE_BACKGROUND_GRADIENT:	gdkColor = COLOR_TITLE_INACTIVE_BACKGROUND_GRADIENT; break;
		case SWT.COLOR_WIDGET_DARK_SHADOW:					gdkColor = COLOR_WIDGET_DARK_SHADOW; break;
		case SWT.COLOR_WIDGET_NORMAL_SHADOW:				gdkColor = COLOR_WIDGET_NORMAL_SHADOW; break;
		case SWT.COLOR_WIDGET_LIGHT_SHADOW: 				gdkColor = COLOR_WIDGET_LIGHT_SHADOW; break;
		case SWT.COLOR_WIDGET_HIGHLIGHT_SHADOW:				gdkColor = COLOR_WIDGET_HIGHLIGHT_SHADOW; break;
		case SWT.COLOR_WIDGET_BACKGROUND: 					gdkColor = COLOR_WIDGET_BACKGROUND; break;
		case SWT.COLOR_WIDGET_FOREGROUND: 					gdkColor = COLOR_WIDGET_FOREGROUND; break;
		case SWT.COLOR_WIDGET_BORDER: 						gdkColor = COLOR_WIDGET_BORDER; break;
		case SWT.COLOR_LIST_FOREGROUND: 					gdkColor = COLOR_LIST_FOREGROUND; break;
		case SWT.COLOR_LIST_BACKGROUND: 					gdkColor = COLOR_LIST_BACKGROUND; break;
		case SWT.COLOR_LIST_SELECTION: 						gdkColor = COLOR_LIST_SELECTION; break;
		case SWT.COLOR_LIST_SELECTION_TEXT: 				gdkColor = COLOR_LIST_SELECTION_TEXT; break;
		default:
			return super.getSystemColor (id);	
	}
	if (gdkColor == null) return super.getSystemColor (SWT.COLOR_BLACK);
	return Color.gtk_new (this, gdkColor);
}

/**
 * Returns the matching standard platform cursor for the given
 * constant, which should be one of the cursor constants
 * specified in class <code>SWT</code>. This cursor should
 * not be free'd because it was allocated by the system,
 * not the application.  A value of <code>null</code> will
 * be returned if the supplied constant is not an SWT cursor
 * constant. 
 *
 * @param id the SWT cursor constant
 * @return the corresponding cursor or <code>null</code>
 *
 * @exception SWTException <ul>
 *    <li>ERROR_THREAD_INVALID_ACCESS - if not called from the thread that created the receiver</li>
 *    <li>ERROR_DEVICE_DISPOSED - if the receiver has been disposed</li>
 * </ul>
 *
 * @see SWT#CURSOR_ARROW
 * @see SWT#CURSOR_WAIT
 * @see SWT#CURSOR_CROSS
 * @see SWT#CURSOR_APPSTARTING
 * @see SWT#CURSOR_HELP
 * @see SWT#CURSOR_SIZEALL
 * @see SWT#CURSOR_SIZENESW
 * @see SWT#CURSOR_SIZENS
 * @see SWT#CURSOR_SIZENWSE
 * @see SWT#CURSOR_SIZEWE
 * @see SWT#CURSOR_SIZEN
 * @see SWT#CURSOR_SIZES
 * @see SWT#CURSOR_SIZEE
 * @see SWT#CURSOR_SIZEW
 * @see SWT#CURSOR_SIZENE
 * @see SWT#CURSOR_SIZESE
 * @see SWT#CURSOR_SIZESW
 * @see SWT#CURSOR_SIZENW
 * @see SWT#CURSOR_UPARROW
 * @see SWT#CURSOR_IBEAM
 * @see SWT#CURSOR_NO
 * @see SWT#CURSOR_HAND
 * 
 * @since 3.0
 */
public Cursor getSystemCursor (int id) {
	checkDevice ();
	if (!(0 <= id && id < cursors.length)) return null;
	if (cursors [id] == null) {
		cursors [id] = new Cursor (this, id);
	}
	return cursors [id];
}

/**
 * Returns the matching standard platform image for the given
 * constant, which should be one of the icon constants
 * specified in class <code>SWT</code>. This image should
 * not be free'd because it was allocated by the system,
 * not the application.  A value of <code>null</code> will
 * be returned either if the supplied constant is not an
 * SWT icon constant or if the platform does not define an
 * image that corresponds to the constant. 
 *
 * @param id the SWT icon constant
 * @return the corresponding image or <code>null</code>
 *
 * @exception SWTException <ul>
 *    <li>ERROR_THREAD_INVALID_ACCESS - if not called from the thread that created the receiver</li>
 *    <li>ERROR_DEVICE_DISPOSED - if the receiver has been disposed</li>
 * </ul>
 *
 * @see SWT#ICON_ERROR
 * @see SWT#ICON_INFORMATION
 * @see SWT#ICON_QUESTION
 * @see SWT#ICON_WARNING
 * @see SWT#ICON_WORKING
 * 
 * @since 3.0
 */
public Image getSystemImage (int id) {
	checkDevice ();
	switch (id) {
		case SWT.ICON_ERROR:
			if (errorImage == null) {
				errorImage = createImage ("gtk-dialog-error"); //$NON-NLS-1$
			}
			return errorImage;
		case SWT.ICON_INFORMATION:
		case SWT.ICON_WORKING:
			if (infoImage == null) {
				infoImage = createImage ("gtk-dialog-info"); //$NON-NLS-1$
			}
			return infoImage;
		case SWT.ICON_QUESTION:
			if (questionImage == null) {
				questionImage = createImage ("gtk-dialog-question"); //$NON-NLS-1$
			}
			return questionImage;
		case SWT.ICON_WARNING:
			if (warningImage == null) {
				warningImage = createImage ("gtk-dialog-warning"); //$NON-NLS-1$
			}
			return warningImage;
	}
	return null;
}

/**
 * Returns the single instance of the system-provided menu for the application, or
 * <code>null</code> on platforms where no menu is provided for the application.
 *
 * @return the system menu, or <code>null</code>
 * 
 * @exception SWTException <ul>
 *    <li>ERROR_THREAD_INVALID_ACCESS - if not called from the thread that created the receiver</li>
 *    <li>ERROR_DEVICE_DISPOSED - if the receiver has been disposed</li>
 * </ul>
 *
 * @since 3.7
 */
public Menu getSystemMenu () {
	checkDevice();
	return null;
}

GdkColor toGdkColor (GdkRGBA rgba) {
	GdkColor gdkColor = new GdkColor();
	gdkColor.red = (short)(rgba.red * 0xFFFF);
	gdkColor.green = (short)(rgba.green * 0xFFFF);
	gdkColor.blue = (short)(rgba.blue * 0xFFFF);
	return gdkColor;
}

GdkColor toGdkColor (GdkRGBA rgba, double m) {
	double[] h = new double[1];
	double[] s = new double[1];
	double[] v = new double[1];
	OS.gtk_rgb_to_hsv(rgba.red, rgba.green, rgba.blue, h, s, v);
	double L = (2 - s[0]) * v[0];
	double S = s[0] * v[0];
	S /= (L <= 1) ? L : 2 - L;
	L /= 2;
	S = Math.max(0f, Math.min(1f, S * m));
	L = Math.max(0f, Math.min(1f, L * m));
	L *= 2;
	S *= L <= 1 ? L : 2 - L;
	v[0] = (L + S) / 2;
	s[0] = (2 * S) / (L + S);
	OS.gtk_hsv_to_rgb(h[0], s[0], v[0], h, s, v);
	GdkColor gdkColor = new GdkColor();
	gdkColor.red = (short)(h[0] * 0xFFFF);
	gdkColor.green = (short)(s[0] * 0xFFFF);
	gdkColor.blue = (short)(v[0] * 0xFFFF);
	return gdkColor;
}

void getBackgroundColor (long /*int*/ context, int state, GdkRGBA rgba) {
	/*
	* Draw the context background to an offset screen surface and get the color
	* in the middle of the surface. 
	*/
	OS.gtk_style_context_save (context);
	OS.gtk_style_context_set_state (context, state);
	long /*int*/ surface = Cairo.cairo_image_surface_create (Cairo.CAIRO_FORMAT_RGB24, 1, 1);
	long /*int*/ cairo = Cairo.cairo_create (surface);
	OS.gtk_render_background (context, cairo, -50, -50, 100, 100);
	Cairo.cairo_fill (cairo);
	Cairo.cairo_surface_flush (surface);
	byte[] buffer = new byte[3];
	OS.memmove (buffer, Cairo.cairo_image_surface_get_data(surface), buffer.length);
	rgba.red = buffer[2] / 255f;
	rgba.green = buffer[1] / 255f;
	rgba.blue = buffer[0] / 255f;
	rgba.alpha = 1;
	Cairo.cairo_surface_destroy (surface);
	Cairo.cairo_destroy (cairo);
	OS.gtk_style_context_restore (context);
}

void initializeSystemColors () {
	long /*int*/ tooltipShellHandle = OS.gtk_window_new (OS.GTK_WINDOW_POPUP);
	if (tooltipShellHandle == 0) error (SWT.ERROR_NO_HANDLES);
	byte[] gtk_tooltip = Converter.wcsToMbcs (null, OS.GTK3 ? "gtk-tooltip" : "gtk-tooltips", true); //$NON-NLS-1$
	OS.gtk_widget_set_name (tooltipShellHandle, gtk_tooltip);
	OS.gtk_widget_realize (tooltipShellHandle);
	
	/* Initialize link foreground */
	long /*int*/ linkWidget = OS.gtk_label_new (new byte[1]);
	if (linkWidget == 0) error (SWT.ERROR_NO_HANDLES);
	OS.gtk_container_add (tooltipShellHandle, linkWidget);
	long /*int*/ [] linkColor = new long /*int*/ [1];
	OS.gtk_widget_style_get (linkWidget, OS.link_color, linkColor, 0);
	GdkColor gdkColor = new GdkColor();
	if (linkColor [0] != 0) {
		OS.memmove (gdkColor, linkColor[0], GdkColor.sizeof);
		OS.gdk_color_free (linkColor [0]);
	} else {
		gdkColor.blue = (short)0xeeee;
	}
	long /*int*/ colormap = OS.gdk_colormap_get_system();
	OS.gdk_colormap_alloc_color(colormap, gdkColor, true, true);
	COLOR_LINK_FOREGROUND = gdkColor;

	if (OS.GTK3) {
		long /*int*/ context = OS.gtk_widget_get_style_context (tooltipShellHandle);
		OS.gtk_style_context_add_class (context, OS.GTK_STYLE_CLASS_TOOLTIP);
		OS.gtk_style_context_invalidate(context);
		GdkRGBA rgba = new GdkRGBA();
		OS.gtk_style_context_get_color (context, OS.GTK_STATE_FLAG_NORMAL, rgba);
		COLOR_INFO_FOREGROUND = toGdkColor (rgba);
		getBackgroundColor (context, OS.GTK_STATE_FLAG_NORMAL, rgba);
		COLOR_INFO_BACKGROUND = toGdkColor (rgba);
		OS.gtk_widget_destroy (tooltipShellHandle);	

		context = OS.gtk_widget_get_style_context (shellHandle);
		
		COLOR_WIDGET_DARK_SHADOW = toGdkColor (new GdkRGBA());
		OS.gtk_style_context_get_background_color (context, OS.GTK_STATE_FLAG_NORMAL, rgba);
		COLOR_WIDGET_LIGHT_SHADOW = toGdkColor (rgba);
		COLOR_WIDGET_NORMAL_SHADOW = toGdkColor (rgba, 0.7);
		COLOR_WIDGET_HIGHLIGHT_SHADOW = toGdkColor (rgba, 1.3);
		
		OS.gtk_style_context_get_color (context, OS.GTK_STATE_FLAG_NORMAL, rgba);
		COLOR_WIDGET_FOREGROUND = toGdkColor (rgba);
		OS.gtk_style_context_get_background_color (context, OS.GTK_STATE_FLAG_NORMAL, rgba);
		COLOR_WIDGET_BACKGROUND = toGdkColor (rgba);
		
		OS.gtk_style_context_save (context);
		OS.gtk_style_context_add_class(context, OS.GTK_STYLE_CLASS_VIEW);
		OS.gtk_style_context_add_class(context, OS.GTK_STYLE_CLASS_CELL);
		OS.gtk_style_context_invalidate(context);
		OS.gtk_style_context_get_color (context, OS.GTK_STATE_FLAG_NORMAL, rgba);
		COLOR_LIST_FOREGROUND = toGdkColor (rgba);
		OS.gtk_style_context_get_background_color (context, OS.GTK_STATE_FLAG_NORMAL, rgba);
		COLOR_LIST_BACKGROUND = toGdkColor (rgba);
		OS.gtk_style_context_restore (context);
		OS.gtk_style_context_get_color (context, OS.GTK_STATE_FLAG_SELECTED, rgba);
		COLOR_LIST_SELECTION_TEXT = toGdkColor (rgba);
		OS.gtk_style_context_get_background_color (context, OS.GTK_STATE_FLAG_SELECTED, rgba);
		COLOR_LIST_SELECTION = toGdkColor (rgba);
		OS.gtk_style_context_get_color (context, OS.GTK_STATE_FLAG_ACTIVE, rgba);
		COLOR_LIST_SELECTION_TEXT_INACTIVE = toGdkColor (rgba);
		OS.gtk_style_context_get_background_color (context, OS.GTK_STATE_FLAG_ACTIVE, rgba);
		COLOR_LIST_SELECTION_INACTIVE = toGdkColor (rgba);
		
		COLOR_TITLE_FOREGROUND = COLOR_LIST_SELECTION_TEXT;
		COLOR_TITLE_BACKGROUND = COLOR_LIST_SELECTION;
		OS.gtk_style_context_get_background_color (context, OS.GTK_STATE_FLAG_SELECTED, rgba);
		COLOR_TITLE_BACKGROUND_GRADIENT = toGdkColor (rgba, 1.3);
		
		OS.gtk_style_context_get_color (context, OS.GTK_STATE_FLAG_INSENSITIVE, rgba);
		COLOR_TITLE_INACTIVE_FOREGROUND = toGdkColor (rgba);
		OS.gtk_style_context_get_background_color (context, OS.GTK_STATE_FLAG_INSENSITIVE, rgba);
		COLOR_TITLE_INACTIVE_BACKGROUND = toGdkColor (rgba);
		COLOR_TITLE_INACTIVE_BACKGROUND_GRADIENT = toGdkColor (rgba, 1.3);
		return;
	}

	/* Get Tooltip resources */
	long /*int*/ tooltipStyle = OS.gtk_widget_get_style (tooltipShellHandle);
	gdkColor = new GdkColor();
	OS.gtk_style_get_fg (tooltipStyle, OS.GTK_STATE_NORMAL, gdkColor);
	COLOR_INFO_FOREGROUND = gdkColor;
	gdkColor = new GdkColor();
	OS.gtk_style_get_bg (tooltipStyle, OS.GTK_STATE_NORMAL, gdkColor);
	COLOR_INFO_BACKGROUND = gdkColor;
	OS.gtk_widget_destroy (tooltipShellHandle);	

	/* Get Shell resources */
	long /*int*/ style = OS.gtk_widget_get_style (shellHandle);	
	gdkColor = new GdkColor();
	OS.gtk_style_get_black (style, gdkColor);
	COLOR_WIDGET_DARK_SHADOW = gdkColor;
	gdkColor = new GdkColor();
	OS.gtk_style_get_dark (style, OS.GTK_STATE_NORMAL, gdkColor);
	COLOR_WIDGET_NORMAL_SHADOW = gdkColor;
	gdkColor = new GdkColor();
	OS.gtk_style_get_bg (style, OS.GTK_STATE_NORMAL, gdkColor);
	COLOR_WIDGET_LIGHT_SHADOW = gdkColor;
	gdkColor = new GdkColor();
	OS.gtk_style_get_light (style, OS.GTK_STATE_NORMAL, gdkColor);
	COLOR_WIDGET_HIGHLIGHT_SHADOW = gdkColor;
	gdkColor = new GdkColor();
	OS.gtk_style_get_fg (style, OS.GTK_STATE_NORMAL, gdkColor);
	COLOR_WIDGET_FOREGROUND = gdkColor;
	gdkColor = new GdkColor();
	OS.gtk_style_get_bg (style, OS.GTK_STATE_NORMAL, gdkColor);
	COLOR_WIDGET_BACKGROUND = gdkColor;
	//gdkColor = new GdkColor();
	//OS.gtk_style_get_text (style, OS.GTK_STATE_NORMAL, gdkColor);
	//COLOR_TEXT_FOREGROUND = gdkColor;
	//gdkColor = new GdkColor();
	//OS.gtk_style_get_base (style, OS.GTK_STATE_NORMAL, gdkColor);
	//COLOR_TEXT_BACKGROUND = gdkColor;
	gdkColor = new GdkColor();
	OS.gtk_style_get_text (style, OS.GTK_STATE_NORMAL, gdkColor);
	COLOR_LIST_FOREGROUND = gdkColor;
	gdkColor = new GdkColor();
	OS.gtk_style_get_base (style, OS.GTK_STATE_NORMAL, gdkColor);
	COLOR_LIST_BACKGROUND = gdkColor;
	gdkColor = new GdkColor();
	OS.gtk_style_get_text (style, OS.GTK_STATE_SELECTED, gdkColor);
	COLOR_LIST_SELECTION_TEXT = gdkColor;
	gdkColor = new GdkColor();
	OS.gtk_style_get_base (style, OS.GTK_STATE_SELECTED, gdkColor);
	COLOR_LIST_SELECTION = gdkColor;
	gdkColor = new GdkColor();
	OS.gtk_style_get_text (style, OS.GTK_STATE_ACTIVE, gdkColor);
	COLOR_LIST_SELECTION_TEXT_INACTIVE = gdkColor;
	gdkColor = new GdkColor();
	OS.gtk_style_get_base (style, OS.GTK_STATE_ACTIVE, gdkColor);
	COLOR_LIST_SELECTION_INACTIVE = gdkColor;
	gdkColor = new GdkColor();
	OS.gtk_style_get_bg (style, OS.GTK_STATE_SELECTED, gdkColor);
	COLOR_TITLE_BACKGROUND = gdkColor;
	gdkColor = new GdkColor();
	OS.gtk_style_get_fg (style, OS.GTK_STATE_SELECTED, gdkColor);
	COLOR_TITLE_FOREGROUND = gdkColor;
	gdkColor = new GdkColor();
	OS.gtk_style_get_light (style, OS.GTK_STATE_SELECTED, gdkColor);
	COLOR_TITLE_BACKGROUND_GRADIENT = gdkColor;
	gdkColor = new GdkColor();
	OS.gtk_style_get_bg (style, OS.GTK_STATE_INSENSITIVE, gdkColor);
	COLOR_TITLE_INACTIVE_BACKGROUND = gdkColor;
	gdkColor = new GdkColor();
	OS.gtk_style_get_fg (style, OS.GTK_STATE_INSENSITIVE, gdkColor);
	COLOR_TITLE_INACTIVE_FOREGROUND = gdkColor;
	gdkColor = new GdkColor();
	OS.gtk_style_get_light (style, OS.GTK_STATE_INSENSITIVE, gdkColor);
	COLOR_TITLE_INACTIVE_BACKGROUND_GRADIENT = gdkColor;
}

/**
 * Returns the single instance of the system taskBar or null
 * when there is no system taskBar available for the platform.
 *
 * @return the system taskBar or <code>null</code>
 * 
 * @exception SWTException <ul>
 *    <li>ERROR_DEVICE_DISPOSED - if the receiver has been disposed</li>
 * </ul>
 *
 * @since 3.6
 */
public TaskBar getSystemTaskBar () {
	checkDevice ();
	return null;
}

/**
 * Returns the single instance of the system tray or null
 * when there is no system tray available for the platform.
 *
 * @return the system tray or <code>null</code>
 * 
 * @exception SWTException <ul>
 *    <li>ERROR_DEVICE_DISPOSED - if the receiver has been disposed</li>
 * </ul>
 *
 * @since 3.0
 */
public Tray getSystemTray () {
	checkDevice ();
	if (tray != null) return tray;
	return tray = new Tray (this, SWT.NONE);
}

/**
 * Returns the user-interface thread for the receiver.
 *
 * @return the receiver's user-interface thread
 * 
 * @exception SWTException <ul>
 *    <li>ERROR_DEVICE_DISPOSED - if the receiver has been disposed</li>
 * </ul>
 */
public Thread getThread () {
	synchronized (Device.class) {
		if (isDisposed ()) error (SWT.ERROR_DEVICE_DISPOSED);
		return thread;
	}
}

/**	 
 * Returns a boolean indicating whether a touch-aware input device is
 * attached to the system and is ready for use.
 *
 * @return <code>true</code> if a touch-aware input device is detected, or <code>false</code> otherwise
 *
 * @exception SWTException <ul>
 *    <li>ERROR_THREAD_INVALID_ACCESS - if not called from the thread that created the receiver</li>
 *    <li>ERROR_DEVICE_DISPOSED - if the receiver has been disposed</li>
 * </ul>
 * 
 * @since 3.7
 */
public boolean getTouchEnabled() {
	checkDevice();
	return false;
}

Widget getWidget (long /*int*/ handle) {
	if (handle == 0) return null;
	if (lastWidget != null && lastHandle == handle) return lastWidget;
	long /*int*/ index = OS.g_object_get_qdata (handle, SWT_OBJECT_INDEX) - 1;
	if (0 <= index && index < widgetTable.length) {
		lastHandle = handle;
		return lastWidget = widgetTable [(int)/*64*/index];
	}
	return null;	
}

long /*int*/ idleProc (long /*int*/ data) {
	boolean result = runAsyncMessages (false);
	if (!result) {
		synchronized (idleLock) {
			idleHandle = 0;
		}
	}
	return result ? 1 : 0;
}

/**
 * Initializes any internal resources needed by the
 * device.
 * <p>
 * This method is called after <code>create</code>.
 * </p>
 * 
 * @see #create
 */
protected void init () {
	super.init ();
	initializeCallbacks ();
	initializeSubclasses ();
	initializeSystemColors ();
	initializeSystemSettings ();
	initializeWidgetTable ();
	initializeWindowManager ();
}

void initializeCallbacks () {
	closures = new long /*int*/ [Widget.LAST_SIGNAL];
	signalIds = new int [Widget.LAST_SIGNAL];

	/* Cache signals for GtkWidget */
	signalIds [Widget.BUTTON_PRESS_EVENT] = OS.g_signal_lookup (OS.button_press_event, OS.GTK_TYPE_WIDGET ());
	signalIds [Widget.BUTTON_RELEASE_EVENT] = OS.g_signal_lookup (OS.button_release_event, OS.GTK_TYPE_WIDGET ());
	signalIds [Widget.CONFIGURE_EVENT] = OS.g_signal_lookup (OS.configure_event, OS.GTK_TYPE_WIDGET ());
	signalIds [Widget.DELETE_EVENT] = OS.g_signal_lookup (OS.delete_event, OS.GTK_TYPE_WIDGET ());
	signalIds [Widget.ENTER_NOTIFY_EVENT] = OS.g_signal_lookup (OS.enter_notify_event, OS.GTK_TYPE_WIDGET ());
	signalIds [Widget.EVENT] = OS.g_signal_lookup (OS.event, OS.GTK_TYPE_WIDGET ());
	signalIds [Widget.EVENT_AFTER] = OS.g_signal_lookup (OS.event_after, OS.GTK_TYPE_WIDGET ());
	signalIds [Widget.EXPOSE_EVENT] = OS.g_signal_lookup (OS.GTK3 ? OS.draw : OS.expose_event, OS.GTK_TYPE_WIDGET ());
	signalIds [Widget.FOCUS] = OS.g_signal_lookup (OS.focus, OS.GTK_TYPE_WIDGET ());
	signalIds [Widget.FOCUS_IN_EVENT] = OS.g_signal_lookup (OS.focus_in_event, OS.GTK_TYPE_WIDGET ());
	signalIds [Widget.FOCUS_OUT_EVENT] = OS.g_signal_lookup (OS.focus_out_event, OS.GTK_TYPE_WIDGET ());
	signalIds [Widget.GRAB_FOCUS] = OS.g_signal_lookup (OS.grab_focus, OS.GTK_TYPE_WIDGET ());
	signalIds [Widget.HIDE] = OS.g_signal_lookup (OS.hide, OS.GTK_TYPE_WIDGET ());
	signalIds [Widget.KEY_PRESS_EVENT] = OS.g_signal_lookup (OS.key_press_event, OS.GTK_TYPE_WIDGET ());
	signalIds [Widget.KEY_RELEASE_EVENT] = OS.g_signal_lookup (OS.key_release_event, OS.GTK_TYPE_WIDGET ());
	signalIds [Widget.LEAVE_NOTIFY_EVENT] = OS.g_signal_lookup (OS.leave_notify_event, OS.GTK_TYPE_WIDGET ());
	signalIds [Widget.MAP] = OS.g_signal_lookup (OS.map, OS.GTK_TYPE_WIDGET ());
	signalIds [Widget.MAP_EVENT] = OS.g_signal_lookup (OS.map_event, OS.GTK_TYPE_WIDGET ());
	signalIds [Widget.MNEMONIC_ACTIVATE] = OS.g_signal_lookup (OS.mnemonic_activate, OS.GTK_TYPE_WIDGET ());
	signalIds [Widget.MOTION_NOTIFY_EVENT] = OS.g_signal_lookup (OS.motion_notify_event, OS.GTK_TYPE_WIDGET ());
	signalIds [Widget.POPUP_MENU] = OS.g_signal_lookup (OS.popup_menu, OS.GTK_TYPE_WIDGET ());
	signalIds [Widget.REALIZE] = OS.g_signal_lookup (OS.realize, OS.GTK_TYPE_WIDGET ());
	signalIds [Widget.SCROLL_EVENT] = OS.g_signal_lookup (OS.scroll_event, OS.GTK_TYPE_WIDGET ());
	signalIds [Widget.SHOW] = OS.g_signal_lookup (OS.show, OS.GTK_TYPE_WIDGET ());
	signalIds [Widget.SHOW_HELP] = OS.g_signal_lookup (OS.show_help, OS.GTK_TYPE_WIDGET ());
	signalIds [Widget.SIZE_ALLOCATE] = OS.g_signal_lookup (OS.size_allocate, OS.GTK_TYPE_WIDGET ());
	signalIds [Widget.STYLE_SET] = OS.g_signal_lookup (OS.style_set, OS.GTK_TYPE_WIDGET ());
	signalIds [Widget.UNMAP] = OS.g_signal_lookup (OS.unmap, OS.GTK_TYPE_WIDGET ());
	signalIds [Widget.UNMAP_EVENT] = OS.g_signal_lookup (OS.unmap_event, OS.GTK_TYPE_WIDGET ());
	signalIds [Widget.UNREALIZE] = OS.g_signal_lookup (OS.realize, OS.GTK_TYPE_WIDGET ());
	signalIds [Widget.VISIBILITY_NOTIFY_EVENT] = OS.g_signal_lookup (OS.visibility_notify_event, OS.GTK_TYPE_WIDGET ());
	signalIds [Widget.WINDOW_STATE_EVENT] = OS.g_signal_lookup (OS.window_state_event, OS.GTK_TYPE_WIDGET ());

	windowCallback2 = new Callback (this, "windowProc", 2); //$NON-NLS-1$
	windowProc2 = windowCallback2.getAddress ();
	if (windowProc2 == 0) error (SWT.ERROR_NO_MORE_CALLBACKS);

	closures [Widget.ACTIVATE] = OS.g_cclosure_new (windowProc2, Widget.ACTIVATE, 0);
	closures [Widget.ACTIVATE_INVERSE] = OS.g_cclosure_new (windowProc2, Widget.ACTIVATE_INVERSE, 0);
	closures [Widget.CHANGED] = OS.g_cclosure_new (windowProc2, Widget.CHANGED, 0);
	closures [Widget.CLICKED] = OS.g_cclosure_new (windowProc2, Widget.CLICKED, 0);
	closures [Widget.CREATE_MENU_PROXY] = OS.g_cclosure_new (windowProc2, Widget.CREATE_MENU_PROXY, 0);
	closures [Widget.DAY_SELECTED] = OS.g_cclosure_new (windowProc2, Widget.DAY_SELECTED, 0);
	closures [Widget.DAY_SELECTED_DOUBLE_CLICK] = OS.g_cclosure_new (windowProc2, Widget.DAY_SELECTED_DOUBLE_CLICK, 0);
	closures [Widget.HIDE] = OS.g_cclosure_new (windowProc2, Widget.HIDE, 0);
	closures [Widget.GRAB_FOCUS] = OS.g_cclosure_new (windowProc2, Widget.GRAB_FOCUS, 0);
	closures [Widget.MAP] = OS.g_cclosure_new (windowProc2, Widget.MAP, 0);
	closures [Widget.MONTH_CHANGED] = OS.g_cclosure_new (windowProc2, Widget.MONTH_CHANGED, 0);
	closures [Widget.OUTPUT] = OS.g_cclosure_new (windowProc2, Widget.OUTPUT, 0);
	closures [Widget.POPUP_MENU] = OS.g_cclosure_new (windowProc2, Widget.POPUP_MENU, 0);
	closures [Widget.PREEDIT_CHANGED] = OS.g_cclosure_new (windowProc2, Widget.PREEDIT_CHANGED, 0);
	closures [Widget.REALIZE] = OS.g_cclosure_new (windowProc2, Widget.REALIZE, 0);
	closures [Widget.SELECT] = OS.g_cclosure_new (windowProc2, Widget.SELECT, 0);
	closures [Widget.SELECTION_DONE] = OS.g_cclosure_new (windowProc2, Widget.SELECTION_DONE, 0);
	closures [Widget.SHOW] = OS.g_cclosure_new (windowProc2, Widget.SHOW, 0);
	closures [Widget.START_INTERACTIVE_SEARCH] = OS.g_cclosure_new (windowProc2, Widget.START_INTERACTIVE_SEARCH, 0);
	closures [Widget.VALUE_CHANGED] = OS.g_cclosure_new (windowProc2, Widget.VALUE_CHANGED, 0);
	closures [Widget.UNMAP] = OS.g_cclosure_new (windowProc2, Widget.UNMAP, 0);
	closures [Widget.UNREALIZE] = OS.g_cclosure_new (windowProc2, Widget.UNREALIZE, 0);
	closures [Widget.BACKSPACE] = OS.g_cclosure_new (windowProc2, Widget.BACKSPACE, 0);
	closures [Widget.BACKSPACE_INVERSE] = OS.g_cclosure_new (windowProc2, Widget.BACKSPACE_INVERSE, 0);
	closures [Widget.COPY_CLIPBOARD] = OS.g_cclosure_new (windowProc2, Widget.COPY_CLIPBOARD, 0);
	closures [Widget.COPY_CLIPBOARD_INVERSE] = OS.g_cclosure_new (windowProc2, Widget.COPY_CLIPBOARD_INVERSE, 0);
	closures [Widget.CUT_CLIPBOARD] = OS.g_cclosure_new (windowProc2, Widget.CUT_CLIPBOARD, 0);
	closures [Widget.CUT_CLIPBOARD_INVERSE] = OS.g_cclosure_new (windowProc2, Widget.CUT_CLIPBOARD_INVERSE, 0);
	closures [Widget.PASTE_CLIPBOARD] = OS.g_cclosure_new (windowProc2, Widget.PASTE_CLIPBOARD, 0);
	closures [Widget.PASTE_CLIPBOARD_INVERSE] = OS.g_cclosure_new (windowProc2, Widget.PASTE_CLIPBOARD_INVERSE, 0);

	windowCallback3 = new Callback (this, "windowProc", 3); //$NON-NLS-1$
	windowProc3 = windowCallback3.getAddress ();
	if (windowProc3 == 0) error (SWT.ERROR_NO_MORE_CALLBACKS);	

	closures [Widget.BUTTON_PRESS_EVENT] = OS.g_cclosure_new (windowProc3, Widget.BUTTON_PRESS_EVENT, 0);
	closures [Widget.BUTTON_PRESS_EVENT_INVERSE] = OS.g_cclosure_new (windowProc3, Widget.BUTTON_PRESS_EVENT_INVERSE, 0);
	closures [Widget.BUTTON_RELEASE_EVENT] = OS.g_cclosure_new (windowProc3, Widget.BUTTON_RELEASE_EVENT, 0);
	closures [Widget.BUTTON_RELEASE_EVENT_INVERSE] = OS.g_cclosure_new (windowProc3, Widget.BUTTON_RELEASE_EVENT_INVERSE, 0);
	closures [Widget.COMMIT] = OS.g_cclosure_new (windowProc3, Widget.COMMIT, 0);
	closures [Widget.CONFIGURE_EVENT] = OS.g_cclosure_new (windowProc3, Widget.CONFIGURE_EVENT, 0);
	closures [Widget.DELETE_EVENT] = OS.g_cclosure_new (windowProc3, Widget.DELETE_EVENT, 0);
	closures [Widget.ENTER_NOTIFY_EVENT] = OS.g_cclosure_new (windowProc3, Widget.ENTER_NOTIFY_EVENT, 0);
	closures [Widget.EVENT] = OS.g_cclosure_new (windowProc3, Widget.EVENT, 0);
	closures [Widget.EVENT_AFTER] = OS.g_cclosure_new (windowProc3, Widget.EVENT_AFTER, 0);
	closures [Widget.EXPOSE_EVENT] = OS.g_cclosure_new (windowProc3, Widget.EXPOSE_EVENT, 0);
	closures [Widget.EXPOSE_EVENT_INVERSE] = OS.g_cclosure_new (windowProc3, Widget.EXPOSE_EVENT_INVERSE, 0);
	closures [Widget.FOCUS] = OS.g_cclosure_new (windowProc3, Widget.FOCUS, 0);
	closures [Widget.FOCUS_IN_EVENT] = OS.g_cclosure_new (windowProc3, Widget.FOCUS_IN_EVENT, 0);
	closures [Widget.FOCUS_OUT_EVENT] = OS.g_cclosure_new (windowProc3, Widget.FOCUS_OUT_EVENT, 0);
	closures [Widget.KEY_PRESS_EVENT] = OS.g_cclosure_new (windowProc3, Widget.KEY_PRESS_EVENT, 0);
	closures [Widget.KEY_RELEASE_EVENT] = OS.g_cclosure_new (windowProc3, Widget.KEY_RELEASE_EVENT, 0);
	closures [Widget.INPUT] = OS.g_cclosure_new (windowProc3, Widget.INPUT, 0);
	closures [Widget.LEAVE_NOTIFY_EVENT] = OS.g_cclosure_new (windowProc3, Widget.LEAVE_NOTIFY_EVENT, 0);
	closures [Widget.MAP_EVENT] = OS.g_cclosure_new (windowProc3, Widget.MAP_EVENT, 0);
	closures [Widget.MNEMONIC_ACTIVATE] = OS.g_cclosure_new (windowProc3, Widget.MNEMONIC_ACTIVATE, 0);
	closures [Widget.MOTION_NOTIFY_EVENT] = OS.g_cclosure_new (windowProc3, Widget.MOTION_NOTIFY_EVENT, 0);
	closures [Widget.MOTION_NOTIFY_EVENT_INVERSE] = OS.g_cclosure_new (windowProc3, Widget.MOTION_NOTIFY_EVENT_INVERSE, 0);
	closures [Widget.MOVE_FOCUS] = OS.g_cclosure_new (windowProc3, Widget.MOVE_FOCUS, 0);
	closures [Widget.POPULATE_POPUP] = OS.g_cclosure_new (windowProc3, Widget.POPULATE_POPUP, 0);
	closures [Widget.SCROLL_EVENT] = OS.g_cclosure_new (windowProc3, Widget.SCROLL_EVENT, 0);
	closures [Widget.SHOW_HELP] = OS.g_cclosure_new (windowProc3, Widget.SHOW_HELP, 0);
	closures [Widget.SIZE_ALLOCATE] = OS.g_cclosure_new (windowProc3, Widget.SIZE_ALLOCATE, 0);
	closures [Widget.STYLE_SET] = OS.g_cclosure_new (windowProc3, Widget.STYLE_SET, 0);
	closures [Widget.TOGGLED] = OS.g_cclosure_new (windowProc3, Widget.TOGGLED, 0);	
	closures [Widget.UNMAP_EVENT] = OS.g_cclosure_new (windowProc3, Widget.UNMAP_EVENT, 0);
	closures [Widget.VISIBILITY_NOTIFY_EVENT] = OS.g_cclosure_new (windowProc3, Widget.VISIBILITY_NOTIFY_EVENT, 0);
	closures [Widget.WINDOW_STATE_EVENT] = OS.g_cclosure_new (windowProc3, Widget.WINDOW_STATE_EVENT, 0);
	closures [Widget.ROW_DELETED] = OS.g_cclosure_new (windowProc3, Widget.ROW_DELETED, 0);
	closures [Widget.DIRECTION_CHANGED] = OS.g_cclosure_new (windowProc3, Widget.DIRECTION_CHANGED, 0);

	windowCallback4 = new Callback (this, "windowProc", 4); //$NON-NLS-1$
	windowProc4 = windowCallback4.getAddress ();
	if (windowProc4 == 0) error (SWT.ERROR_NO_MORE_CALLBACKS);	

	closures [Widget.DELETE_RANGE] = OS.g_cclosure_new (windowProc4, Widget.DELETE_RANGE, 0);
	closures [Widget.DELETE_TEXT] = OS.g_cclosure_new (windowProc4, Widget.DELETE_TEXT, 0);
	closures [Widget.ICON_RELEASE] = OS.g_cclosure_new (windowProc4, Widget.ICON_RELEASE, 0);
	closures [Widget.ROW_ACTIVATED] = OS.g_cclosure_new (windowProc4, Widget.ROW_ACTIVATED, 0);
	closures [Widget.SCROLL_CHILD] = OS.g_cclosure_new (windowProc4, Widget.SCROLL_CHILD, 0);
	closures [Widget.STATUS_ICON_POPUP_MENU] = OS.g_cclosure_new (windowProc4, Widget.STATUS_ICON_POPUP_MENU, 0);
	closures [Widget.SWITCH_PAGE] = OS.g_cclosure_new (windowProc4, Widget.SWITCH_PAGE, 0);
	closures [Widget.TEST_COLLAPSE_ROW] = OS.g_cclosure_new (windowProc4, Widget.TEST_COLLAPSE_ROW, 0);
	closures [Widget.TEST_EXPAND_ROW] = OS.g_cclosure_new (windowProc4, Widget.TEST_EXPAND_ROW, 0);
	closures [Widget.ROW_INSERTED] = OS.g_cclosure_new (windowProc4, Widget.ROW_INSERTED, 0);
	closures [Widget.DELETE_FROM_CURSOR] = OS.g_cclosure_new (windowProc4, Widget.DELETE_FROM_CURSOR, 0);
	closures [Widget.DELETE_FROM_CURSOR_INVERSE] = OS.g_cclosure_new (windowProc4, Widget.DELETE_FROM_CURSOR_INVERSE, 0);

	windowCallback5 = new Callback (this, "windowProc", 5); //$NON-NLS-1$
	windowProc5 = windowCallback5.getAddress ();
	if (windowProc5 == 0) error (SWT.ERROR_NO_MORE_CALLBACKS);

	closures [Widget.CHANGE_VALUE] = OS.g_cclosure_new (windowProc5, Widget.CHANGE_VALUE, 0);
	closures [Widget.EXPAND_COLLAPSE_CURSOR_ROW] = OS.g_cclosure_new (windowProc5, Widget.EXPAND_COLLAPSE_CURSOR_ROW, 0);
	closures [Widget.INSERT_TEXT] = OS.g_cclosure_new (windowProc5, Widget.INSERT_TEXT, 0);
	closures [Widget.TEXT_BUFFER_INSERT_TEXT] = OS.g_cclosure_new (windowProc5, Widget.TEXT_BUFFER_INSERT_TEXT, 0);
	closures [Widget.MOVE_CURSOR] = OS.g_cclosure_new (windowProc5, Widget.MOVE_CURSOR, 0);
	closures [Widget.MOVE_CURSOR_INVERSE] = OS.g_cclosure_new (windowProc5, Widget.MOVE_CURSOR_INVERSE, 0);

	for (int i = 0; i < Widget.LAST_SIGNAL; i++) {
		if (closures [i] != 0) OS.g_closure_ref (closures [i]);
	}

	timerCallback = new Callback (this, "timerProc", 1); //$NON-NLS-1$
	timerProc = timerCallback.getAddress ();
	if (timerProc == 0) error (SWT.ERROR_NO_MORE_CALLBACKS);

	windowTimerCallback = new Callback (this, "windowTimerProc", 1); //$NON-NLS-1$
	windowTimerProc = windowTimerCallback.getAddress ();
	if (windowTimerProc == 0) error (SWT.ERROR_NO_MORE_CALLBACKS);
	
	mouseHoverCallback = new Callback (this, "mouseHoverProc", 1); //$NON-NLS-1$
	mouseHoverProc = mouseHoverCallback.getAddress ();
	if (mouseHoverProc == 0) error (SWT.ERROR_NO_MORE_CALLBACKS);

	caretCallback = new Callback(this, "caretProc", 1); //$NON-NLS-1$
	caretProc = caretCallback.getAddress();
	if (caretProc == 0) error (SWT.ERROR_NO_MORE_CALLBACKS);

	menuPositionCallback = new Callback(this, "menuPositionProc", 5); //$NON-NLS-1$
	menuPositionProc = menuPositionCallback.getAddress();
	if (menuPositionProc == 0) error (SWT.ERROR_NO_MORE_CALLBACKS);
	
	sizeAllocateCallback = new Callback(this, "sizeAllocateProc", 3); //$NON-NLS-1$
	sizeAllocateProc = sizeAllocateCallback.getAddress();
	if (sizeAllocateProc == 0) error (SWT.ERROR_NO_MORE_CALLBACKS);
	
	sizeRequestCallback = new Callback(this, "sizeRequestProc", 3); //$NON-NLS-1$
	sizeRequestProc = sizeRequestCallback.getAddress();
	if (sizeRequestProc == 0) error (SWT.ERROR_NO_MORE_CALLBACKS);
	
	shellMapCallback = new Callback(this, "shellMapProc", 3); //$NON-NLS-1$
	shellMapProc = shellMapCallback.getAddress();
	if (shellMapProc == 0) error (SWT.ERROR_NO_MORE_CALLBACKS);

	shellMapProcClosure = OS.g_cclosure_new (shellMapProc, 0, 0);
	OS.g_closure_ref (shellMapProcClosure);
	
	cellDataCallback = new Callback (this, "cellDataProc", 5); //$NON-NLS-1$
	cellDataProc = cellDataCallback.getAddress ();
	if (cellDataProc == 0) error (SWT.ERROR_NO_MORE_CALLBACKS);

	setDirectionCallback = new Callback (this, "setDirectionProc", 2); //$NON-NLS-1$
	setDirectionProc = setDirectionCallback.getAddress ();
	if (setDirectionProc == 0) error (SWT.ERROR_NO_MORE_CALLBACKS);
	
	emissionProcCallback = new Callback (this, "emissionProc", 4); //$NON-NLS-1$
	emissionProc = emissionProcCallback.getAddress ();
	if (emissionProc == 0) error (SWT.ERROR_NO_MORE_CALLBACKS);

	allChildrenCallback = new Callback (this, "allChildrenProc", 2); //$NON-NLS-1$
	allChildrenProc = allChildrenCallback.getAddress ();
	if (allChildrenProc == 0) error (SWT.ERROR_NO_MORE_CALLBACKS);

	checkIfEventCallback = new Callback (this, "checkIfEventProc", 3); //$NON-NLS-1$
	checkIfEventProc = checkIfEventCallback.getAddress ();
	if (checkIfEventProc == 0) error (SWT.ERROR_NO_MORE_CALLBACKS);

	idleCallback = new Callback (this, "idleProc", 1); //$NON-NLS-1$
	idleProc = idleCallback.getAddress ();
	if (idleProc == 0) error (SWT.ERROR_NO_MORE_CALLBACKS);
}

void initializeSubclasses () {
	long /*int*/ pangoLayoutType = OS.PANGO_TYPE_LAYOUT ();
	long /*int*/ pangoLayoutClass = OS.g_type_class_ref (pangoLayoutType);
	pangoLayoutNewProc = OS.G_OBJECT_CLASS_CONSTRUCTOR (pangoLayoutClass);
	OS.G_OBJECT_CLASS_SET_CONSTRUCTOR (pangoLayoutClass, OS.pangoLayoutNewProc_CALLBACK(pangoLayoutNewProc));
	OS.g_type_class_unref (pangoLayoutClass);

	if (OS.GTK3) {
		long /*int*/ imContextType = OS.GTK_TYPE_IM_MULTICONTEXT ();
		long /*int*/ imContextClass = OS.g_type_class_ref (imContextType);
		imContextNewProc = OS.G_OBJECT_CLASS_CONSTRUCTOR (imContextClass);
		OS.G_OBJECT_CLASS_SET_CONSTRUCTOR (imContextClass, OS.imContextNewProc_CALLBACK(imContextNewProc));
		OS.g_type_class_unref (imContextClass);
	}
}

void initializeSystemSettings () {
	OS.g_signal_connect (shellHandle, OS.style_set, signalProc, STYLE_SET);
	
	/*
	* Feature in GTK.  Despite the fact that the
	* gtk-entry-select-on-focus property is a global
	* setting, it is initialized when the GtkEntry
	* is initialized.  This means that it cannot be
	* accessed before a GtkEntry is created.  The
	* fix is to for the initializaion by creating
	* a temporary GtkEntry.
	*/
	long /*int*/ entry = OS.gtk_entry_new ();
	OS.gtk_widget_destroy (entry);
	int [] buffer2 = new int [1];
	long /*int*/ settings = OS.gtk_settings_get_default ();
	OS.g_object_get (settings, OS.gtk_entry_select_on_focus, buffer2, 0);
	entrySelectOnFocus = buffer2 [0] != 0;
}

void initializeWidgetTable () {
	indexTable = new int [GROW_SIZE];
	widgetTable = new Widget [GROW_SIZE];
	for (int i=0; i<GROW_SIZE-1; i++) indexTable [i] = i + 1;
	indexTable [GROW_SIZE - 1] = -1;
}

void initializeWindowManager () {
	/* Get the window manager name */
	windowManager = ""; //$NON-NLS-1$
	long /*int*/ screen = OS.gdk_screen_get_default ();
	if (screen != 0) {
		long /*int*/ ptr2 = OS.gdk_x11_screen_get_window_manager_name (screen);
		if (ptr2 != 0) {
			int length = OS.strlen (ptr2);
			if (length > 0) {
				byte [] buffer2 = new byte [length];
				OS.memmove (buffer2, ptr2, length);
				windowManager = new String (Converter.mbcsToWcs (null, buffer2));
			}
		}
	}
}

/**	 
 * Invokes platform specific functionality to dispose a GC handle.
 * <p>
 * <b>IMPORTANT:</b> This method is <em>not</em> part of the public
 * API for <code>Display</code>. It is marked public only so that it
 * can be shared within the packages provided by SWT. It is not
 * available on all platforms, and should never be called from
 * application code.
 * </p>
 *
 * @param hDC the platform specific GC handle
 * @param data the platform specific GC data 
 * 
 * @noreference This method is not intended to be referenced by clients.
 */
public void internal_dispose_GC (long /*int*/ hDC, GCData data) {
	long /*int*/ gc = hDC;
	if (OS.USE_CAIRO) {
		Cairo.cairo_destroy (gc);
	} else {
		OS.g_object_unref (gc);
	}
}

/**	 
 * Invokes platform specific functionality to allocate a new GC handle.
 * <p>
 * <b>IMPORTANT:</b> This method is <em>not</em> part of the public
 * API for <code>Display</code>. It is marked public only so that it
 * can be shared within the packages provided by SWT. It is not
 * available on all platforms, and should never be called from
 * application code.
 * </p>
 *
 * @param data the platform specific GC data 
 * @return the platform specific GC handle
 * 
 * @exception SWTException <ul>
 *    <li>ERROR_DEVICE_DISPOSED - if the receiver has been disposed</li>
 * </ul>
 * @exception SWTError <ul>
 *    <li>ERROR_NO_HANDLES if a handle could not be obtained for gc creation</li>
 * </ul>
 * 
 * @noreference This method is not intended to be referenced by clients.
 */
public long /*int*/ internal_new_GC (GCData data) {
	if (isDisposed()) error(SWT.ERROR_DEVICE_DISPOSED);
	long /*int*/ root = OS.gdk_get_default_root_window();
	long /*int*/ gc;
	if (OS.USE_CAIRO) {
		gc = OS.gdk_cairo_create (root);
		if (gc == 0) error (SWT.ERROR_NO_HANDLES);
		//TODO how gdk_gc_set_subwindow is done in cairo?
	} else {
		gc = OS.gdk_gc_new (root);
		if (gc == 0) error (SWT.ERROR_NO_HANDLES);
		OS.gdk_gc_set_subwindow (gc, OS.GDK_INCLUDE_INFERIORS);
	}
	if (data != null) {
		int mask = SWT.LEFT_TO_RIGHT | SWT.RIGHT_TO_LEFT;
		if ((data.style & mask) == 0) {
			data.style |= SWT.LEFT_TO_RIGHT;
		}
		data.device = this;
		data.drawable = root;
		data.background = getSystemColor (SWT.COLOR_WHITE).handle;
		data.foreground = getSystemColor (SWT.COLOR_BLACK).handle;
		data.font = getSystemFont ();
	}
	return gc;
}

boolean isValidThread () {
	return thread == Thread.currentThread ();
}

/**
 * Maps a point from one coordinate system to another.
 * When the control is null, coordinates are mapped to
 * the display.
 * <p>
 * NOTE: On right-to-left platforms where the coordinate
 * systems are mirrored, special care needs to be taken
 * when mapping coordinates from one control to another
 * to ensure the result is correctly mirrored.
 * 
 * Mapping a point that is the origin of a rectangle and
 * then adding the width and height is not equivalent to
 * mapping the rectangle.  When one control is mirrored
 * and the other is not, adding the width and height to a
 * point that was mapped causes the rectangle to extend
 * in the wrong direction.  Mapping the entire rectangle
 * instead of just one point causes both the origin and
 * the corner of the rectangle to be mapped.
 * </p>
 * 
 * @param from the source <code>Control</code> or <code>null</code>
 * @param to the destination <code>Control</code> or <code>null</code>
 * @param point to be mapped 
 * @return point with mapped coordinates 
 * 
 * @exception IllegalArgumentException <ul>
 *    <li>ERROR_NULL_ARGUMENT - if the point is null</li>
 *    <li>ERROR_INVALID_ARGUMENT - if the Control from or the Control to have been disposed</li> 
 * </ul>
 * @exception SWTException <ul>
 *    <li>ERROR_THREAD_INVALID_ACCESS - if not called from the thread that created the receiver</li>
 *    <li>ERROR_DEVICE_DISPOSED - if the receiver has been disposed</li>
 * </ul>
 * 
 * @since 2.1.2
 */
public Point map (Control from, Control to, Point point) {
	checkDevice ();
	if (point == null) error (SWT.ERROR_NULL_ARGUMENT);	
	return map (from, to, point.x, point.y);
}

/**
 * Maps a point from one coordinate system to another.
 * When the control is null, coordinates are mapped to
 * the display.
 * <p>
 * NOTE: On right-to-left platforms where the coordinate
 * systems are mirrored, special care needs to be taken
 * when mapping coordinates from one control to another
 * to ensure the result is correctly mirrored.
 * 
 * Mapping a point that is the origin of a rectangle and
 * then adding the width and height is not equivalent to
 * mapping the rectangle.  When one control is mirrored
 * and the other is not, adding the width and height to a
 * point that was mapped causes the rectangle to extend
 * in the wrong direction.  Mapping the entire rectangle
 * instead of just one point causes both the origin and
 * the corner of the rectangle to be mapped.
 * </p>
 * 
 * @param from the source <code>Control</code> or <code>null</code>
 * @param to the destination <code>Control</code> or <code>null</code>
 * @param x coordinates to be mapped
 * @param y coordinates to be mapped
 * @return point with mapped coordinates
 * 
 * @exception IllegalArgumentException <ul>
 *    <li>ERROR_INVALID_ARGUMENT - if the Control from or the Control to have been disposed</li> 
 * </ul>
 * @exception SWTException <ul>
 *    <li>ERROR_THREAD_INVALID_ACCESS - if not called from the thread that created the receiver</li>
 *    <li>ERROR_DEVICE_DISPOSED - if the receiver has been disposed</li>
 * </ul>
 * 
 * @since 2.1.2
 */
public Point map (Control from, Control to, int x, int y) {
	checkDevice ();
	if (from != null && from.isDisposed()) error (SWT.ERROR_INVALID_ARGUMENT);
	if (to != null && to.isDisposed()) error (SWT.ERROR_INVALID_ARGUMENT);
	Point point = new Point (x, y);
	if (from == to) return point;
	if (from != null) {
		long /*int*/ window = from.eventWindow ();
		int [] origin_x = new int [1], origin_y = new int [1];
		OS.gdk_window_get_origin (window, origin_x, origin_y);
		if ((from.style & SWT.MIRRORED) != 0) point.x = from.getClientWidth () - point.x;
		point.x += origin_x [0];
		point.y += origin_y [0];
	}
	if (to != null) {
		long /*int*/ window = to.eventWindow ();
		int [] origin_x = new int [1], origin_y = new int [1];
		OS.gdk_window_get_origin (window, origin_x, origin_y);
		point.x -= origin_x [0];
		point.y -= origin_y [0];
		if ((to.style & SWT.MIRRORED) != 0) point.x = to.getClientWidth () - point.x;
	}
	return point;
}

/**
 * Maps a point from one coordinate system to another.
 * When the control is null, coordinates are mapped to
 * the display.
 * <p>
 * NOTE: On right-to-left platforms where the coordinate
 * systems are mirrored, special care needs to be taken
 * when mapping coordinates from one control to another
 * to ensure the result is correctly mirrored.
 * 
 * Mapping a point that is the origin of a rectangle and
 * then adding the width and height is not equivalent to
 * mapping the rectangle.  When one control is mirrored
 * and the other is not, adding the width and height to a
 * point that was mapped causes the rectangle to extend
 * in the wrong direction.  Mapping the entire rectangle
 * instead of just one point causes both the origin and
 * the corner of the rectangle to be mapped.
 * </p>
 * 
 * @param from the source <code>Control</code> or <code>null</code>
 * @param to the destination <code>Control</code> or <code>null</code>
 * @param rectangle to be mapped
 * @return rectangle with mapped coordinates
 * 
 * @exception IllegalArgumentException <ul>
 *    <li>ERROR_NULL_ARGUMENT - if the rectangle is null</li>
 *    <li>ERROR_INVALID_ARGUMENT - if the Control from or the Control to have been disposed</li> 
 * </ul>
 * @exception SWTException <ul>
 *    <li>ERROR_THREAD_INVALID_ACCESS - if not called from the thread that created the receiver</li>
 *    <li>ERROR_DEVICE_DISPOSED - if the receiver has been disposed</li>
 * </ul>
 * 
 * @since 2.1.2
 */
public Rectangle map (Control from, Control to, Rectangle rectangle) {
	checkDevice();
	if (rectangle == null) error (SWT.ERROR_NULL_ARGUMENT);
	return map (from, to, rectangle.x, rectangle.y, rectangle.width, rectangle.height);
}

static char mbcsToWcs (char ch) {
	int key = ch & 0xFFFF;
	if (key <= 0x7F) return ch;
	byte [] buffer;
	if (key <= 0xFF) {
		buffer = new byte [1];
		buffer [0] = (byte) key;
	} else {
		buffer = new byte [2];
		buffer [0] = (byte) ((key >> 8) & 0xFF);
		buffer [1] = (byte) (key & 0xFF);
	}
	char [] result = Converter.mbcsToWcs (null, buffer);
	if (result.length == 0) return 0;
	return result [0];
}

long /*int*/ menuPositionProc (long /*int*/ menu, long /*int*/ x, long /*int*/ y, long /*int*/ push_in, long /*int*/ user_data) {
	Widget widget = getWidget (menu);
	if (widget == null) return 0;
	return widget.menuPositionProc (menu, x, y, push_in, user_data);	
}

/**
 * Maps a point from one coordinate system to another.
 * When the control is null, coordinates are mapped to
 * the display.
 * <p>
 * NOTE: On right-to-left platforms where the coordinate
 * systems are mirrored, special care needs to be taken
 * when mapping coordinates from one control to another
 * to ensure the result is correctly mirrored.
 * 
 * Mapping a point that is the origin of a rectangle and
 * then adding the width and height is not equivalent to
 * mapping the rectangle.  When one control is mirrored
 * and the other is not, adding the width and height to a
 * point that was mapped causes the rectangle to extend
 * in the wrong direction.  Mapping the entire rectangle
 * instead of just one point causes both the origin and
 * the corner of the rectangle to be mapped.
 * </p>
 * 
 * @param from the source <code>Control</code> or <code>null</code>
 * @param to the destination <code>Control</code> or <code>null</code>
 * @param x coordinates to be mapped
 * @param y coordinates to be mapped
 * @param width coordinates to be mapped
 * @param height coordinates to be mapped
 * @return rectangle with mapped coordinates
 * 
 * @exception IllegalArgumentException <ul>
 *    <li>ERROR_INVALID_ARGUMENT - if the Control from or the Control to have been disposed</li> 
 * </ul>
 * @exception SWTException <ul>
 *    <li>ERROR_THREAD_INVALID_ACCESS - if not called from the thread that created the receiver</li>
 *    <li>ERROR_DEVICE_DISPOSED - if the receiver has been disposed</li>
 * </ul>
 * 
 * @since 2.1.2
 */
public Rectangle map (Control from, Control to, int x, int y, int width, int height) {
	checkDevice();
	if (from != null && from.isDisposed()) error (SWT.ERROR_INVALID_ARGUMENT);
	if (to != null && to.isDisposed()) error (SWT.ERROR_INVALID_ARGUMENT);
	Rectangle rect = new Rectangle (x, y, width, height);
	if (from == to) return rect;
	boolean fromRTL = false, toRTL = false;
	if (from != null) {
		long /*int*/ window = from.eventWindow ();
		int [] origin_x = new int [1], origin_y = new int [1];
		OS.gdk_window_get_origin (window, origin_x, origin_y);
		if (fromRTL = (from.style & SWT.MIRRORED) != 0) rect.x = from.getClientWidth() - rect.x;
		rect.x += origin_x [0];
		rect.y += origin_y [0];
	}
	if (to != null) {
		long /*int*/ window = to.eventWindow ();
		int [] origin_x = new int [1], origin_y = new int [1];
		OS.gdk_window_get_origin (window, origin_x, origin_y);
		rect.x -= origin_x [0];
		rect.y -= origin_y [0];
		if (toRTL = (to.style & SWT.MIRRORED) != 0) rect.x = to.getClientWidth () - rect.x;
	}
	if (fromRTL != toRTL) rect.x -= rect.width;
	return rect;
}

long /*int*/ mouseHoverProc (long /*int*/ handle) {
	Widget widget = getWidget (handle);
	if (widget == null) return 0;
	return widget.hoverProc (handle);
}

/**
 * Generate a low level system event.
 * 
 * <code>post</code> is used to generate low level keyboard
 * and mouse events. The intent is to enable automated UI
 * testing by simulating the input from the user.  Most
 * SWT applications should never need to call this method.
 * <p>
 * Note that this operation can fail when the operating system
 * fails to generate the event for any reason.  For example,
 * this can happen when there is no such key or mouse button
 * or when the system event queue is full.
 * </p>
 * <p>
 * <b>Event Types:</b>
 * <p>KeyDown, KeyUp
 * <p>The following fields in the <code>Event</code> apply:
 * <ul>
 * <li>(in) type KeyDown or KeyUp</li>
 * <p> Either one of:
 * <li>(in) character a character that corresponds to a keyboard key</li>
 * <li>(in) keyCode the key code of the key that was typed,
 *          as defined by the key code constants in class <code>SWT</code></li>
 * </ul>
 * <p>MouseDown, MouseUp</p>
 * <p>The following fields in the <code>Event</code> apply:
 * <ul>
 * <li>(in) type MouseDown or MouseUp
 * <li>(in) button the button that is pressed or released
 * </ul>
 * <p>MouseMove</p>
 * <p>The following fields in the <code>Event</code> apply:
 * <ul>
 * <li>(in) type MouseMove
 * <li>(in) x the x coordinate to move the mouse pointer to in screen coordinates
 * <li>(in) y the y coordinate to move the mouse pointer to in screen coordinates
 * </ul>
 * <p>MouseWheel</p>
 * <p>The following fields in the <code>Event</code> apply:
 * <ul>
 * <li>(in) type MouseWheel
 * <li>(in) detail either SWT.SCROLL_LINE or SWT.SCROLL_PAGE
 * <li>(in) count the number of lines or pages to scroll
 * </ul>
 * </dl>
 * 
 * @param event the event to be generated
 * 
 * @return true if the event was generated or false otherwise
 * 
 * @exception IllegalArgumentException <ul>
 *    <li>ERROR_NULL_ARGUMENT - if the event is null</li>
 * </ul>
 * @exception SWTException <ul>
 *    <li>ERROR_DEVICE_DISPOSED - if the receiver has been disposed</li>
 * </ul>
 *
 * @since 3.0
 * 
 */
public boolean post (Event event) {
	/*
	* Get the operating system lock before synchronizing on the device
	* lock so that the device lock will not be held should another
	* thread already be in the operating system.  This avoids deadlock
	* should the other thread need the device lock.
	*/
	Lock lock = OS.lock;
	lock.lock();
	try {
		synchronized (Device.class) {
			if (isDisposed ()) error (SWT.ERROR_DEVICE_DISPOSED);
			if (event == null) error (SWT.ERROR_NULL_ARGUMENT);
			if (!OS.GDK_WINDOWING_X11()) return false;
			long /*int*/ xDisplay = OS.gdk_x11_display_get_xdisplay(OS.gdk_display_get_default());
			int type = event.type;
			switch (type) {
				case SWT.KeyDown:
				case SWT.KeyUp: {
					int keyCode = 0;
					long /*int*/ keysym = untranslateKey (event.keyCode);
					if (keysym != 0) keyCode = OS.XKeysymToKeycode (xDisplay, keysym);
					if (keyCode == 0) {
						char key = event.character;
						switch (key) {
							case SWT.BS: keysym = OS.GDK_BackSpace; break;
							case SWT.CR: keysym = OS.GDK_Return; break;
							case SWT.DEL: keysym = OS.GDK_Delete; break;
							case SWT.ESC: keysym = OS.GDK_Escape; break;
							case SWT.TAB: keysym = OS.GDK_Tab; break;
							case SWT.LF: keysym = OS.GDK_Linefeed; break;
							default:
								keysym = key;
						}
						keyCode = OS.XKeysymToKeycode (xDisplay, keysym);
						if (keyCode == 0) return false;
					}
					OS.XTestFakeKeyEvent (xDisplay, keyCode, type == SWT.KeyDown, 0);
					return true;
				}
				case SWT.MouseDown:
				case SWT.MouseMove: 
				case SWT.MouseUp: {
					if (type == SWT.MouseMove) {
						OS.XTestFakeMotionEvent (xDisplay, -1, event.x, event.y, 0);
					} else {
						int button = event.button;
						switch (button) {
							case 1:
							case 2:
							case 3:	break;
							case 4: button = 6;	break;
							case 5: button = 7;	break;
							default: return false;
						}
						OS.XTestFakeButtonEvent (xDisplay, button, type == SWT.MouseDown, 0);
					}
					return true;
				}
				/*
				* This code is intentionally commented. After posting a
				* mouse wheel event the system may respond unpredictably
				* to subsequent mouse actions.
				*/
//				case SWT.MouseWheel: {
//					if (event.count == 0) return false;
//					int button = event.count < 0 ? 5 : 4;
//					OS.XTestFakeButtonEvent (xDisplay, button, type == SWT.MouseWheel, 0);			
//				}
			}
			return false;
		}
	} finally {
		lock.unlock();
	}
}

void postEvent (Event event) {
	/*
	* Place the event at the end of the event queue.
	* This code is always called in the Display's
	* thread so it must be re-enterant but does not
	* need to be synchronized.
	*/
	if (eventQueue == null) eventQueue = new Event [4];
	int index = 0;
	int length = eventQueue.length;
	while (index < length) {
		if (eventQueue [index] == null) break;
		index++;
	}
	if (index == length) {
		Event [] newQueue = new Event [length + 4];
		System.arraycopy (eventQueue, 0, newQueue, 0, length);
		eventQueue = newQueue;
	}
	eventQueue [index] = event;
}

void putGdkEvents () {
	if (gdkEventCount != 0) {
		for (int i = 0; i < gdkEventCount; i++) {
			long /*int*/ event = gdkEvents [i];
			Widget widget = gdkEventWidgets [i];
			if (widget == null || !widget.isDisposed ()) {
				OS.gdk_event_put (event);
			}
			OS.gdk_event_free (event);
			gdkEvents [i] = 0;
			gdkEventWidgets [i] = null;
		}
		gdkEventCount = 0;
	}
}

/**
 * Reads an event from the operating system's event queue,
 * dispatches it appropriately, and returns <code>true</code>
 * if there is potentially more work to do, or <code>false</code>
 * if the caller can sleep until another event is placed on
 * the event queue.
 * <p>
 * In addition to checking the system event queue, this method also
 * checks if any inter-thread messages (created by <code>syncExec()</code>
 * or <code>asyncExec()</code>) are waiting to be processed, and if
 * so handles them before returning.
 * </p>
 *
 * @return <code>false</code> if the caller can sleep upon return from this method
 *
 * @exception SWTException <ul>
 *    <li>ERROR_THREAD_INVALID_ACCESS - if not called from the thread that created the receiver</li>
 *    <li>ERROR_DEVICE_DISPOSED - if the receiver has been disposed</li>
 *    <li>ERROR_FAILED_EXEC - if an exception occurred while running an inter-thread message</li>
 * </ul>
 *
 * @see #sleep
 * @see #wake
 */
public boolean readAndDispatch () {
	checkDevice ();
	runSkin ();
	runDeferredLayouts ();
	boolean events = false;
	events |= runSettings ();
	events |= runPopups ();
	/*
	* This call to gdk_threads_leave() is a temporary work around
	* to avoid deadlocks when gdk_threads_init() is called by native
	* code outside of SWT (i.e AWT, etc). It ensures that the current
	* thread leaves the GTK lock before calling the function below. 
	*/
	OS.gdk_threads_leave();
	events |= OS.g_main_context_iteration (0, false);
	if (events) {
		runDeferredEvents ();
		return true;
	}
	return isDisposed () || runAsyncMessages (false);
}

static void register (Display display) {
	synchronized (Device.class) {
		for (int i=0; i<Displays.length; i++) {
			if (Displays [i] == null) {
				Displays [i] = display;
				return;
			}
		}
		Display [] newDisplays = new Display [Displays.length + 4];
		System.arraycopy (Displays, 0, newDisplays, 0, Displays.length);
		newDisplays [Displays.length] = display;
		Displays = newDisplays;
	}
}

/**
 * Releases any internal resources back to the operating
 * system and clears all fields except the device handle.
 * <p>
 * Disposes all shells which are currently open on the display. 
 * After this method has been invoked, all related related shells
 * will answer <code>true</code> when sent the message
 * <code>isDisposed()</code>.
 * </p><p>
 * When a device is destroyed, resources that were acquired
 * on behalf of the programmer need to be returned to the
 * operating system.  For example, if the device allocated a
 * font to be used as the system font, this font would be
 * freed in <code>release</code>.  Also,to assist the garbage
 * collector and minimize the amount of memory that is not
 * reclaimed when the programmer keeps a reference to a
 * disposed device, all fields except the handle are zero'd.
 * The handle is needed by <code>destroy</code>.
 * </p>
 * This method is called before <code>destroy</code>.
 * 
 * @see Device#dispose
 * @see #destroy
 */
protected void release () {
	sendEvent (SWT.Dispose, new Event ());
	Shell [] shells = getShells ();
	for (int i=0; i<shells.length; i++) {
		Shell shell = shells [i];
		if (!shell.isDisposed ())  shell.dispose ();
	}
	if (tray != null) tray.dispose ();
	tray = null;
	while (readAndDispatch ()) {}
	if (disposeList != null) {
		for (int i=0; i<disposeList.length; i++) {
			if (disposeList [i] != null) disposeList [i].run ();
		}
	}
	disposeList = null;
	synchronizer.releaseSynchronizer ();
	synchronizer = null;
	releaseDisplay ();
	super.release ();
}

void releaseDisplay () {
	windowCallback2.dispose ();  windowCallback2 = null;
	windowCallback3.dispose ();  windowCallback3 = null;
	windowCallback4.dispose ();  windowCallback4 = null;
	windowCallback5.dispose ();  windowCallback5 = null;
	windowProc2 = windowProc3 = windowProc4 = windowProc5 = 0;
	
	/* Dispose xfilter callback */
	if (filterProc != 0) {
		OS.gdk_window_remove_filter(0, filterProc, 0);
	}
	filterCallback.dispose(); filterCallback = null;
	filterProc = 0;

	/* Dispose checkIfEvent callback */
	checkIfEventCallback.dispose(); checkIfEventCallback = null;
	checkIfEventProc = 0;
	
	/* Dispose preedit window */
	if (preeditWindow != 0) OS.gtk_widget_destroy (preeditWindow);
	imControl = null;

	/* Dispose the menu callback */
	menuPositionCallback.dispose (); menuPositionCallback = null;
	menuPositionProc = 0;

	/* Dispose the tooltip map callback */
	sizeAllocateCallback.dispose (); sizeAllocateCallback = null;
	sizeAllocateProc = 0;
	sizeRequestCallback.dispose (); sizeRequestCallback = null;
	sizeRequestProc = 0;
	
	/* Dispose the shell map callback */
	shellMapCallback.dispose (); shellMapCallback = null;
	shellMapProc = 0;
	
	/* Dispose the run async messages callback */
	idleCallback.dispose (); idleCallback = null;
	idleProc = 0;
	if (idleHandle != 0) OS.g_source_remove (idleHandle);
	idleHandle = 0;
	
	/* Dispose GtkTreeView callbacks */
	cellDataCallback.dispose (); cellDataCallback = null;
	cellDataProc = 0;
	
	/* Dispose the set direction callback */
	setDirectionCallback.dispose (); setDirectionCallback = null;
	setDirectionProc = 0;
	
	/* Dispose the emission proc callback */
	emissionProcCallback.dispose (); emissionProcCallback = null;
	emissionProc = 0;

	/* Dispose the set direction callback */
	allChildrenCallback.dispose (); allChildrenCallback = null;
	allChildrenProc = 0;

	/* Dispose the caret callback */
	if (caretId != 0) OS.g_source_remove (caretId);
	caretId = 0;
	caretProc = 0;
	caretCallback.dispose ();
	caretCallback = null;
	
	/* Release closures */
	for (int i = 0; i < Widget.LAST_SIGNAL; i++) {
		if (closures [i] != 0) OS.g_closure_unref (closures [i]);
	}
	if (shellMapProcClosure != 0) OS.g_closure_unref (shellMapProcClosure);

	/* Dispose the timer callback */
	if (timerIds != null) {
		for (int i=0; i<timerIds.length; i++) {
			if (timerIds [i] != 0) OS.g_source_remove (timerIds [i]);
		}
	}
	timerIds = null;
	timerList = null;
	timerProc = 0;
	timerCallback.dispose ();
	timerCallback = null;
	windowTimerProc = 0;
	windowTimerCallback.dispose ();
	windowTimerCallback = null;
	
	/* Dispose mouse hover callback */
	if (mouseHoverId != 0) OS.g_source_remove (mouseHoverId);
	mouseHoverId = 0;
	mouseHoverHandle = mouseHoverProc = 0;
	mouseHoverCallback.dispose ();
	mouseHoverCallback = null;
	
	/* Dispose the System Images */
	if (errorImage != null) errorImage.dispose();
	if (infoImage != null) infoImage.dispose();
	if (questionImage != null) questionImage.dispose();
	if (warningImage != null) warningImage.dispose();
	errorImage = infoImage = questionImage = warningImage = null;
	
	/* Release the System Cursors */
	for (int i = 0; i < cursors.length; i++) {
		if (cursors [i] != null) cursors [i].dispose ();
	}
	cursors = null;

	/* Release Acquired Resources */
	if (resources != null) {
		for (int i=0; i<resources.length; i++) {
			if (resources [i] != null) resources [i].dispose ();
		}
		resources = null;
	}

	/* Release the System Colors */
	COLOR_WIDGET_DARK_SHADOW = COLOR_WIDGET_NORMAL_SHADOW = COLOR_WIDGET_LIGHT_SHADOW =
	COLOR_WIDGET_HIGHLIGHT_SHADOW = COLOR_WIDGET_BACKGROUND = COLOR_WIDGET_BORDER =
	COLOR_LIST_FOREGROUND = COLOR_LIST_BACKGROUND = COLOR_LIST_SELECTION = COLOR_LIST_SELECTION_TEXT =
	COLOR_LIST_SELECTION_INACTIVE = COLOR_LIST_SELECTION_TEXT_INACTIVE =
	COLOR_WIDGET_FOREGROUND = COLOR_TITLE_FOREGROUND = COLOR_TITLE_BACKGROUND = COLOR_TITLE_BACKGROUND_GRADIENT =
	COLOR_TITLE_INACTIVE_FOREGROUND = COLOR_TITLE_INACTIVE_BACKGROUND = COLOR_TITLE_INACTIVE_BACKGROUND_GRADIENT =
	COLOR_INFO_BACKGROUND = COLOR_INFO_FOREGROUND = COLOR_LINK_FOREGROUND = null;

	/* Dispose the event callback */
	OS.gdk_event_handler_set (0, 0, 0);
	eventCallback.dispose ();  eventCallback = null;
	
	/* Dispose the hidden shell */
	if (shellHandle != 0) OS.gtk_widget_destroy (shellHandle);
	shellHandle = 0;
	
	/* Dispose the settings callback */
	signalCallback.dispose(); signalCallback = null;
	signalProc = 0;

	/* Dispose subclass */
	long /*int*/ pangoLayoutType = OS.PANGO_TYPE_LAYOUT ();
	long /*int*/ pangoLayoutClass = OS.g_type_class_ref (pangoLayoutType);
	OS.G_OBJECT_CLASS_SET_CONSTRUCTOR (pangoLayoutClass, pangoLayoutNewProc);
	OS.g_type_class_unref (pangoLayoutClass);
	pangoLayoutNewProc = 0;
	if (OS.GTK3) {
		long /*int*/ imContextType = OS.GTK_TYPE_IM_MULTICONTEXT ();
		long /*int*/ imContextClass = OS.g_type_class_ref (imContextType);
		OS.G_OBJECT_CLASS_SET_CONSTRUCTOR (imContextClass, imContextNewProc);
		OS.g_type_class_unref (imContextClass);
		imContextNewProc = 0;
	}
	
	/* Release the sleep resources */
	max_priority = timeout = null;
	if (fds != 0) OS.g_free (fds);
	fds = 0;

	/* Release references */
	popups = null;
	thread = null;
	lastWidget = activeShell = null;
	flushData = closures = null;
	indexTable = signalIds = null;
	widgetTable = modalShells = null;
	data = null;
	values = keys = null;
	windowManager = null;
	eventTable = filterTable = null;
	modalDialog = null;
	flushRect = null;
	exposeEvent = null;
	visibilityEvent = null;
	idleLock = null;
}

/**
 * Removes the listener from the collection of listeners who will
 * be notified when an event of the given type occurs anywhere in
 * a widget. The event type is one of the event constants defined
 * in class <code>SWT</code>.
 *
 * @param eventType the type of event to listen for
 * @param listener the listener which should no longer be notified when the event occurs
 *
 * @exception IllegalArgumentException <ul>
 *    <li>ERROR_NULL_ARGUMENT - if the listener is null</li>
 * </ul>
 * @exception SWTException <ul>
 *    <li>ERROR_THREAD_INVALID_ACCESS - if not called from the thread that created the receiver</li>
 * </ul>
 *
 * @see Listener
 * @see SWT
 * @see #addFilter
 * @see #addListener
 * 
 * @since 3.0
 */
public void removeFilter (int eventType, Listener listener) {
	checkDevice ();
	if (listener == null) error (SWT.ERROR_NULL_ARGUMENT);
	if (filterTable == null) return;
	filterTable.unhook (eventType, listener);
	if (filterTable.size () == 0) filterTable = null;
}

long /*int*/ removeGdkEvent () {
	if (gdkEventCount == 0) return 0;
	long /*int*/ event = gdkEvents [0];
	--gdkEventCount;
	System.arraycopy (gdkEvents, 1, gdkEvents, 0, gdkEventCount);
	System.arraycopy (gdkEventWidgets, 1, gdkEventWidgets, 0, gdkEventCount);
	gdkEvents [gdkEventCount] = 0;
	gdkEventWidgets [gdkEventCount] = null;
	if (gdkEventCount == 0) {
		gdkEvents = null;
		gdkEventWidgets = null;
	}
	return event;
}

void removeIdleProc () {
	synchronized (idleLock) {
		if (idleHandle != 0) OS.g_source_remove (idleHandle);
		idleNeeded = false;
		idleHandle = 0;
	}
}
/**
 * Removes the listener from the collection of listeners who will
 * be notified when an event of the given type occurs. The event type
 * is one of the event constants defined in class <code>SWT</code>.
 *
 * @param eventType the type of event to listen for
 * @param listener the listener which should no longer be notified
 *
 * @exception IllegalArgumentException <ul>
 *    <li>ERROR_NULL_ARGUMENT - if the listener is null</li>
 * </ul>
 * @exception SWTException <ul>
 *    <li>ERROR_THREAD_INVALID_ACCESS - if not called from the thread that created the receiver</li>
 *    <li>ERROR_DEVICE_DISPOSED - if the receiver has been disposed</li>
 * </ul>
 *
 * @see Listener
 * @see SWT
 * @see #addListener
 * 
 * @since 2.0 
 */
public void removeListener (int eventType, Listener listener) {
	checkDevice ();
	if (listener == null) error (SWT.ERROR_NULL_ARGUMENT);
	if (eventTable == null) return;
	eventTable.unhook (eventType, listener);
}

void removeMouseHoverTimeout (long /*int*/ handle) {
	if (handle != mouseHoverHandle) return;
	if (mouseHoverId != 0) OS.g_source_remove (mouseHoverId);
	mouseHoverId = 0;
	mouseHoverHandle = 0;
}

void removePopup (Menu menu) {
	if (popups == null) return;
	for (int i=0; i<popups.length; i++) {
		if (popups [i] == menu) {
			popups [i] = null;
			return;
		}
	}
}

Widget removeWidget (long /*int*/ handle) {
	if (handle == 0) return null;
	lastWidget = null;
	Widget widget = null;
	int index = (int)/*64*/ OS.g_object_get_qdata (handle, SWT_OBJECT_INDEX) - 1;
	if (0 <= index && index < widgetTable.length) {
		widget = widgetTable [index];
		widgetTable [index] = null;
		indexTable [index] = freeSlot;
		freeSlot = index;
		OS.g_object_set_qdata (handle, SWT_OBJECT_INDEX, 0);
	}
	return widget;	
}

boolean runAsyncMessages (boolean all) {
	return synchronizer.runAsyncMessages (all);
}

boolean runDeferredEvents () {
	boolean run = false;
	/*
	* Run deferred events.  This code is always
	* called in the Display's thread so it must
	* be re-enterant but need not be synchronized.
	*/
	while (eventQueue != null) {
		
		/* Take an event off the queue */
		Event event = eventQueue [0];
		if (event == null) break;
		int length = eventQueue.length;
		System.arraycopy (eventQueue, 1, eventQueue, 0, --length);
		eventQueue [length] = null;

		/* Run the event */
		Widget widget = event.widget;
		if (widget != null && !widget.isDisposed ()) {
			Widget item = event.item;
			if (item == null || !item.isDisposed ()) {
				run = true;
				widget.sendEvent (event);
			}
		}

		/*
		* At this point, the event queue could
		* be null due to a recursive invokation
		* when running the event.
		*/
	}

	/* Clear the queue */
	eventQueue = null;
	return run;
}

boolean runDeferredLayouts () {
	if (layoutDeferredCount != 0) {
		Composite[] temp = layoutDeferred;
		int count = layoutDeferredCount;
		layoutDeferred = null;
		layoutDeferredCount = 0;
		for (int i = 0; i < count; i++) {
			Composite comp = temp[i];
			if (!comp.isDisposed()) comp.setLayoutDeferred (false);
		}
		update ();
		return true;
	}	
	return false;
}

boolean runPopups () {
	if (popups == null) return false;
	boolean result = false;
	while (popups != null) {
		Menu menu = popups [0];
		if (menu == null) break;
		int length = popups.length;
		System.arraycopy (popups, 1, popups, 0, --length);
		popups [length] = null;
		runDeferredEvents ();
		if (!menu.isDisposed ()) menu._setVisible (true);
		result = true;
	}
	popups = null;
	return result;
}

boolean runSettings () {
	if (!runSettings) return false;
	runSettings = false;
	saveResources ();
	initializeSystemColors ();
	sendEvent (SWT.Settings, null);
	Shell [] shells = getShells ();
	for (int i=0; i<shells.length; i++) {
		Shell shell = shells [i];
		if (!shell.isDisposed ()) {
			shell.fixStyle ();
			shell.redraw (true);
			shell.layout (true, true);
		}
	}
	return true;
}

boolean runSkin () {
	if (skinCount > 0) {
		Widget [] oldSkinWidgets = skinList;	
		int count = skinCount;	
		skinList = new Widget[GROW_SIZE];
		skinCount = 0;
		if (eventTable != null && eventTable.hooks(SWT.Skin)) {
			for (int i = 0; i < count; i++) {
				Widget widget = oldSkinWidgets[i];
				if (widget != null && !widget.isDisposed()) {
					widget.state &= ~Widget.SKIN_NEEDED;
					oldSkinWidgets[i] = null;
					Event event = new Event ();
					event.widget = widget;
					sendEvent (SWT.Skin, event);
				}
			}
		}
		return true;
	}	
	return false;
}

/**
 * Returns the application name.
 *
 * @return the application name
 * 
 * @see #setAppName(String)
 * 
 * @since 3.6
 */
public static String getAppName () {
	return APP_NAME;
}
	
/**
 * Returns the application version.
 *
 * @return the application version
 * 
 * @see #setAppVersion(String)
 * 
 * @since 3.6
 */
public static String getAppVersion () {
	return APP_VERSION;
}

/**
 * Sets the application name to the argument.
 * <p>
 * The application name can be used in several ways,
 * depending on the platform and tools being used.
 * On Motif, for example, this can be used to set
 * the name used for resource lookup. Accessibility
 * tools may also ask for the application name.
 * </p><p>
 * Specifying <code>null</code> for the name clears it.
 * </p>
 *
 * @param name the new app name or <code>null</code>
 */
public static void setAppName (String name) {
	APP_NAME = name;
}

/**
 * Sets the application version to the argument.
 *
 * @param version the new app version
 * 
 * @since 3.6
 */
public static void setAppVersion (String version) {
	APP_VERSION = version;
}

/**
 * Sets the location of the on-screen pointer relative to the top left corner
 * of the screen.  <b>Note: It is typically considered bad practice for a
 * program to move the on-screen pointer location.</b>
 *
 * @param x the new x coordinate for the cursor
 * @param y the new y coordinate for the cursor
 * 
 * @exception SWTException <ul>
 *    <li>ERROR_THREAD_INVALID_ACCESS - if not called from the thread that created the receiver</li>
 *    <li>ERROR_DEVICE_DISPOSED - if the receiver has been disposed</li>
 * </ul>
 * 
 * @since 2.1
 */
public void setCursorLocation (int x, int y) {
	checkDevice ();
	if (OS.GDK_WINDOWING_X11 ()) {
		long /*int*/ xDisplay = OS.gdk_x11_display_get_xdisplay(OS.gdk_display_get_default());
		long /*int*/ xWindow = OS.XDefaultRootWindow (xDisplay);
		OS.XWarpPointer (xDisplay, OS.None, xWindow, 0, 0, 0, 0, x, y);
	}
}

/**
 * Sets the location of the on-screen pointer relative to the top left corner
 * of the screen.  <b>Note: It is typically considered bad practice for a
 * program to move the on-screen pointer location.</b>
 *
 * @param point new position
 * 
 * @exception SWTException <ul>
 *    <li>ERROR_THREAD_INVALID_ACCESS - if not called from the thread that created the receiver</li>
 *    <li>ERROR_NULL_ARGUMENT - if the point is null
 *    <li>ERROR_DEVICE_DISPOSED - if the receiver has been disposed</li>
 * </ul>
 * 
 * @since 2.0
 */
public void setCursorLocation (Point point) {
	checkDevice ();
	if (point == null) error (SWT.ERROR_NULL_ARGUMENT);
	setCursorLocation (point.x, point.y);
}

/**
 * Sets the application defined property of the receiver
 * with the specified name to the given argument.
 * <p>
 * Applications may have associated arbitrary objects with the
 * receiver in this fashion. If the objects stored in the
 * properties need to be notified when the display is disposed
 * of, it is the application's responsibility provide a
 * <code>disposeExec()</code> handler which does so.
 * </p>
 *
 * @param key the name of the property
 * @param value the new value for the property
 *
 * @exception IllegalArgumentException <ul>
 *    <li>ERROR_NULL_ARGUMENT - if the key is null</li>
 * </ul>
 * @exception SWTException <ul>
 *    <li>ERROR_THREAD_INVALID_ACCESS - if not called from the thread that created the receiver</li>
 *    <li>ERROR_DEVICE_DISPOSED - if the receiver has been disposed</li>
 * </ul>
 *
 * @see #getData(String)
 * @see #disposeExec(Runnable)
 */
public void setData (String key, Object value) {
	checkDevice ();
	if (key == null) error (SWT.ERROR_NULL_ARGUMENT);

	if (key.equals (DISPATCH_EVENT_KEY)) {
		if (value == null || value instanceof int []) {
			dispatchEvents = (int []) value;
			if (value == null) putGdkEvents ();
			return;
		}
	}
	if (key.equals (SET_MODAL_DIALOG)) {
		setModalDialog ((Dialog) value);
		return;
	}
	if (key.equals (ADD_WIDGET_KEY)) {
		Object [] data = (Object []) value;
		long /*int*/ handle = ((LONG) data [0]).value;
		Widget widget = (Widget) data [1];
		if (widget != null) {
			addWidget (handle, widget);
		} else {
			removeWidget (handle);
		}
	}
	if (key.equals (ADD_IDLE_PROC_KEY)) {	
		addIdleProc ();
		return;
	}
	if (key.equals (REMOVE_IDLE_PROC_KEY)) {
		removeIdleProc ();
		return;
	}

	/* Remove the key/value pair */
	if (value == null) {
		if (keys == null) return;
		int index = 0;
		while (index < keys.length && !keys [index].equals (key)) index++;
		if (index == keys.length) return;
		if (keys.length == 1) {
			keys = null;
			values = null;
		} else {
			String [] newKeys = new String [keys.length - 1];
			Object [] newValues = new Object [values.length - 1];
			System.arraycopy (keys, 0, newKeys, 0, index);
			System.arraycopy (keys, index + 1, newKeys, index, newKeys.length - index);
			System.arraycopy (values, 0, newValues, 0, index);
			System.arraycopy (values, index + 1, newValues, index, newValues.length - index);
			keys = newKeys;
			values = newValues;
		}
		return;
	}
	
	/* Add the key/value pair */
	if (keys == null) {
		keys = new String [] {key};
		values = new Object [] {value};
		return;
	}
	for (int i=0; i<keys.length; i++) {
		if (keys [i].equals (key)) {
			values [i] = value;
			return;
		}
	}
	String [] newKeys = new String [keys.length + 1];
	Object [] newValues = new Object [values.length + 1];
	System.arraycopy (keys, 0, newKeys, 0, keys.length);
	System.arraycopy (values, 0, newValues, 0, values.length);
	newKeys [keys.length] = key;
	newValues [values.length] = value;
	keys = newKeys;
	values = newValues;
}

/**
 * Sets the application defined, display specific data
 * associated with the receiver, to the argument.
 * The <em>display specific data</em> is a single,
 * unnamed field that is stored with every display. 
 * <p>
 * Applications may put arbitrary objects in this field. If
 * the object stored in the display specific data needs to
 * be notified when the display is disposed of, it is the
 * application's responsibility provide a
 * <code>disposeExec()</code> handler which does so.
 * </p>
 *
 * @param data the new display specific data
 *
 * @exception SWTException <ul>
 *    <li>ERROR_THREAD_INVALID_ACCESS - if not called from the thread that created the receiver</li>
 *    <li>ERROR_DEVICE_DISPOSED - if the receiver has been disposed</li>
 * </ul>
 *
 * @see #getData()
 * @see #disposeExec(Runnable)
 */
public void setData (Object data) {
	checkDevice ();
	this.data = data;
}

long /*int*/ setDirectionProc (long /*int*/ widget, long /*int*/ direction) {
	OS.gtk_widget_set_direction (widget, (int)/*64*/ direction);
	if (OS.GTK_IS_MENU_ITEM (widget)) {
		long /*int*/ submenu = OS.gtk_menu_item_get_submenu (widget);
		if (submenu != 0) {
			OS.gtk_widget_set_direction (submenu, (int)/*64*/ direction);
			OS.gtk_container_forall (submenu, setDirectionProc, direction);
		}
	}
	if (OS.GTK_IS_CONTAINER (widget)) {
		OS.gtk_container_forall (widget, setDirectionProc, direction);
	}
	return 0;
}

void setModalDialog (Dialog modalDailog) {
	this.modalDialog = modalDailog;
	Shell [] shells = getShells ();
	for (int i=0; i<shells.length; i++) shells [i].updateModal ();
}

void setModalShell (Shell shell) {
	if (modalShells == null) modalShells = new Shell [4];
	int index = 0, length = modalShells.length;
	while (index < length) {
		if (modalShells [index] == shell) return;
		if (modalShells [index] == null) break;
		index++;
	}
	if (index == length) {
		Shell [] newModalShells = new Shell [length + 4];
		System.arraycopy (modalShells, 0, newModalShells, 0, length);
		modalShells = newModalShells;
	}
	modalShells [index] = shell;
	Shell [] shells = getShells ();
	for (int i=0; i<shells.length; i++) shells [i].updateModal ();
}

/**
 * Sets the synchronizer used by the display to be
 * the argument, which can not be null.
 *
 * @param synchronizer the new synchronizer for the display (must not be null)
 *
 * @exception IllegalArgumentException <ul>
 *    <li>ERROR_NULL_ARGUMENT - if the synchronizer is null</li>
 * </ul>
 * @exception SWTException <ul>
 *    <li>ERROR_THREAD_INVALID_ACCESS - if not called from the thread that created the receiver</li>
 *    <li>ERROR_DEVICE_DISPOSED - if the receiver has been disposed</li>
 *    <li>ERROR_FAILED_EXEC - if an exception occurred while running an inter-thread message</li>
 * </ul>
 */
public void setSynchronizer (Synchronizer synchronizer) {
	checkDevice ();
	if (synchronizer == null) error (SWT.ERROR_NULL_ARGUMENT);
	if (synchronizer == this.synchronizer) return;
	Synchronizer oldSynchronizer;
	synchronized (Device.class) {
		oldSynchronizer = this.synchronizer;
		this.synchronizer = synchronizer;
	}
	if (oldSynchronizer != null) {
		oldSynchronizer.runAsyncMessages(true);
	}
}

void showIMWindow (Control control) {
	imControl = control;
	if (preeditWindow == 0) { 
		preeditWindow = OS.gtk_window_new (OS.GTK_WINDOW_POPUP);
		if (preeditWindow == 0) error (SWT.ERROR_NO_HANDLES);
		preeditLabel = OS.gtk_label_new (null);
		if (preeditLabel == 0) error (SWT.ERROR_NO_HANDLES);
		OS.gtk_container_add (preeditWindow, preeditLabel);
		OS.gtk_widget_show (preeditLabel);
	}
	long /*int*/ [] preeditString = new long /*int*/ [1];
	long /*int*/ [] pangoAttrs = new long /*int*/ [1];
	long /*int*/ imHandle = control.imHandle ();
	OS.gtk_im_context_get_preedit_string (imHandle, preeditString, pangoAttrs, null);
	if (preeditString [0] != 0 && OS.strlen (preeditString [0]) > 0) {
		Control widget = control.findBackgroundControl ();
		if (widget == null) widget = control;
		GdkColor color = widget.getBackgroundColor ();
		if (OS.GTK3) {
			widget.setBackgroundColor (preeditWindow, color);
		} else {
			OS.gtk_widget_modify_bg (preeditWindow,  OS.GTK_STATE_NORMAL, color);
		}
		widget.setForegroundColor (preeditLabel, control.getForegroundColor());
		widget.setFontDescription (preeditLabel, control.getFontDescription ());
		if (pangoAttrs [0] != 0) OS.gtk_label_set_attributes (preeditLabel, pangoAttrs[0]);
		OS.gtk_label_set_text (preeditLabel, preeditString [0]);
		Point point = control.toDisplay (control.getIMCaretPos ());
		OS.gtk_window_move (preeditWindow, point.x, point.y);		
		GtkRequisition requisition = new GtkRequisition ();
		if (OS.GTK3) {
			OS.gtk_widget_get_preferred_size (preeditLabel, requisition, null);
		} else {
			OS.gtk_widget_size_request (preeditLabel, requisition);
		}
		OS.gtk_window_resize (preeditWindow, requisition.width, requisition.height);
		OS.gtk_widget_show (preeditWindow);
	} else {
		OS.gtk_widget_hide (preeditWindow);
	}		
	if (preeditString [0] != 0) OS.g_free (preeditString [0]);
	if (pangoAttrs [0] != 0) OS.pango_attr_list_unref (pangoAttrs [0]);	
}

/**
 * Causes the user-interface thread to <em>sleep</em> (that is,
 * to be put in a state where it does not consume CPU cycles)
 * until an event is received or it is otherwise awakened.
 *
 * @return <code>true</code> if an event requiring dispatching was placed on the queue.
 *
 * @exception SWTException <ul>
 *    <li>ERROR_THREAD_INVALID_ACCESS - if not called from the thread that created the receiver</li>
 *    <li>ERROR_DEVICE_DISPOSED - if the receiver has been disposed</li>
 * </ul>
 *
 * @see #wake
 */
public boolean sleep () {
	checkDevice ();
	if (gdkEventCount == 0) {
		gdkEvents = null;
		gdkEventWidgets = null;
	}
	if (settingsChanged) {
		settingsChanged = false;
		runSettings = true;
		return false;
	}
	if (getMessageCount () != 0) return true;
	if (fds == 0) {
		allocated_nfds = 2;
		fds = OS.g_malloc (OS.GPollFD_sizeof () * allocated_nfds);
	}
	max_priority [0] = timeout [0] = 0;
	long /*int*/ context = OS.g_main_context_default ();
	boolean result = false;
	do {
		if (OS.g_main_context_acquire (context)) {
			result = OS.g_main_context_prepare (context, max_priority);
			int nfds;
			while ((nfds = OS.g_main_context_query (context, max_priority [0], timeout, fds, allocated_nfds)) > allocated_nfds) {
				OS.g_free (fds);
				allocated_nfds = nfds;
				fds = OS.g_malloc (OS.GPollFD_sizeof() * allocated_nfds);
			}
			long /*int*/ poll = OS.g_main_context_get_poll_func (context);
			if (poll != 0) {
				if (nfds > 0 || timeout [0] != 0) {
					/*
					* Bug in GTK. For some reason, g_main_context_wakeup() may 
					* fail to wake up the UI thread from the polling function.
					* The fix is to sleep for a maximum of 50 milliseconds.
					*/
					if (timeout [0] < 0) timeout [0] = 50;
					
					/* Exit the OS lock to allow other threads to enter GTK */
					Lock lock = OS.lock;
					int count = lock.lock ();
					for (int i = 0; i < count; i++) lock.unlock ();
					try {
						wake = false;
						OS.Call (poll, fds, nfds, timeout [0]);
					} finally {
						for (int i = 0; i < count; i++) lock.lock ();
						lock.unlock ();
					}
				}
			}
			OS.g_main_context_check (context, max_priority [0], fds, nfds);
			OS.g_main_context_release (context);
		}
	} while (!result && getMessageCount () == 0 && !wake);
	wake = false;
	return true;
}

/**
 * Causes the <code>run()</code> method of the runnable to
 * be invoked by the user-interface thread after the specified
 * number of milliseconds have elapsed. If milliseconds is less
 * than zero, the runnable is not executed.
 * <p>
 * Note that at the time the runnable is invoked, widgets 
 * that have the receiver as their display may have been
 * disposed. Therefore, it is necessary to check for this
 * case inside the runnable before accessing the widget.
 * </p>
 *
 * @param milliseconds the delay before running the runnable
 * @param runnable code to run on the user-interface thread
 *
 * @exception IllegalArgumentException <ul>
 *    <li>ERROR_NULL_ARGUMENT - if the runnable is null</li>
 * </ul>
 * @exception SWTException <ul>
 *    <li>ERROR_THREAD_INVALID_ACCESS - if not called from the thread that created the receiver</li>
 *    <li>ERROR_DEVICE_DISPOSED - if the receiver has been disposed</li>
 * </ul>
 *
 * @see #asyncExec
 */
public void timerExec (int milliseconds, Runnable runnable) {
	checkDevice ();
	if (runnable == null) error (SWT.ERROR_NULL_ARGUMENT);
	if (timerList == null) timerList = new Runnable [4];
	if (timerIds == null) timerIds = new int [4];	
	int index = 0;
	while (index < timerList.length) {
		if (timerList [index] == runnable) break;
		index++;
	}
	if (index != timerList.length) {
		OS.g_source_remove (timerIds [index]);
		timerList [index] = null;
		timerIds [index] = 0;
		if (milliseconds < 0) return;
	} else {
		if (milliseconds < 0) return;
		index = 0;
		while (index < timerList.length) {
			if (timerList [index] == null) break;
			index++;
		}
		if (index == timerList.length) {
			Runnable [] newTimerList = new Runnable [timerList.length + 4];
			System.arraycopy (timerList, 0, newTimerList, 0, timerList.length);
			timerList = newTimerList;
			int [] newTimerIds = new int [timerIds.length + 4];
			System.arraycopy (timerIds, 0, newTimerIds, 0, timerIds.length);
			timerIds = newTimerIds;
		}
	}
	int timerId = OS.g_timeout_add (milliseconds, timerProc, index);
	if (timerId != 0) {
		timerIds [index] = timerId;
		timerList [index] = runnable;
	}
}

long /*int*/ timerProc (long /*int*/ i) {
	if (timerList == null) return 0;
	int index = (int)/*64*/i;
	if (0 <= index && index < timerList.length) {
		Runnable runnable = timerList [index];
		timerList [index] = null;
		timerIds [index] = 0;
		if (runnable != null) runnable.run ();
	}
	return 0;
}

long /*int*/ caretProc (long /*int*/ clientData) {
	caretId = 0;
	if (currentCaret == null) {
		return 0;
	}
	if (currentCaret.blinkCaret()) {
		int blinkRate = currentCaret.blinkRate;
		if (blinkRate == 0) return 0;
		caretId = OS.g_timeout_add (blinkRate, caretProc, 0);
	} else {
		currentCaret = null;
	}
	return 0;
}

long /*int*/ sizeAllocateProc (long /*int*/ handle, long /*int*/ arg0, long /*int*/ user_data) {
	Widget widget = getWidget (user_data);
	if (widget == null) return 0;
	return widget.sizeAllocateProc (handle, arg0, user_data);
}

long /*int*/ sizeRequestProc (long /*int*/ handle, long /*int*/ arg0, long /*int*/ user_data) {
	Widget widget = getWidget (user_data);
	if (widget == null) return 0;
	return widget.sizeRequestProc (handle, arg0, user_data);
}

void saveResources () {
	int resourceCount = 0;
	if (resources == null) {
		resources = new Resource [RESOURCE_SIZE];
	} else {
		resourceCount = resources.length;
		Resource [] newResources = new Resource [resourceCount + RESOURCE_SIZE];
		System.arraycopy (resources, 0, newResources, 0, resourceCount);
		resources = newResources;
	}
	if (errorImage != null) resources [resourceCount++] = errorImage;
	if (infoImage != null) resources [resourceCount++] = infoImage;
	if (questionImage != null) resources [resourceCount++] = questionImage;
	if (warningImage != null) resources [resourceCount++] = warningImage;
	errorImage = infoImage = questionImage = warningImage = null;
	for (int i=0; i<cursors.length; i++) {
		if (cursors [i] != null) resources [resourceCount++] = cursors [i];
		cursors [i] = null;
	}
	if (resourceCount < RESOURCE_SIZE) {
		Resource [] newResources = new Resource [resourceCount];
		System.arraycopy (resources, 0, newResources, 0, resourceCount);
		resources = newResources;
	}
}

void sendEvent (int eventType, Event event) {
	if (eventTable == null && filterTable == null) {
		return;
	}
	if (event == null) event = new Event ();
	event.display = this;
	event.type = eventType;
	if (event.time == 0) event.time = getLastEventTime ();
	if (!filterEvent (event)) {
		if (eventTable != null) eventTable.sendEvent (event);
	}
}

void setCurrentCaret (Caret caret) {
	if (caretId != 0) OS.g_source_remove(caretId);
	caretId = 0;
	currentCaret = caret;
	if (caret == null) return;
	int blinkRate = currentCaret.blinkRate;
	caretId = OS.g_timeout_add (blinkRate, caretProc, 0); 
}

long /*int*/ shellMapProc (long /*int*/ handle, long /*int*/ arg0, long /*int*/ user_data) {
	Widget widget = getWidget (handle);
	if (widget == null) return 0;
	return widget.shellMapProc (handle, arg0, user_data);
}

long /*int*/ signalProc (long /*int*/ gobject, long /*int*/ arg1, long /*int*/ user_data) {
	switch((int)/*64*/user_data) {
		case STYLE_SET:
			settingsChanged = true;
			break;
		case PROPERTY_NOTIFY:
			GdkEventProperty gdkEvent = new GdkEventProperty ();
			OS.memmove (gdkEvent, arg1);
			if (gdkEvent.type == OS.GDK_PROPERTY_NOTIFY) {
				byte[] name = Converter.wcsToMbcs (null, "org.eclipse.swt.filePath.message", true); //$NON-NLS-1$
				long /*int*/ atom = OS.gdk_x11_atom_to_xatom (OS.gdk_atom_intern (name, true));
				if (atom == OS.gdk_x11_atom_to_xatom (gdkEvent.atom)) {
					long /*int*/ xWindow; 
					if (OS.GTK3) {
						xWindow = OS.gdk_x11_window_get_xid (OS.gtk_widget_get_window (shellHandle));
					} else if (OS.GTK_VERSION >= OS.VERSION(2, 14, 0)){
						xWindow = OS.gdk_x11_drawable_get_xid (OS.gtk_widget_get_window( shellHandle));
					} else {
						xWindow = OS.gdk_x11_drawable_get_xid (OS.GTK_WIDGET_WINDOW( shellHandle));
					}
					long /*int*/ [] type = new long /*int*/ [1];
					int [] format = new int [1];
					int [] nitems = new int [1];
					int [] bytes_after = new int [1];
					long /*int*/ [] data = new long /*int*/ [1];
					OS.XGetWindowProperty (OS.gdk_x11_display_get_xdisplay(OS.gdk_display_get_default()), xWindow, atom, 0, -1, true, OS.AnyPropertyType,
							type, format, nitems, bytes_after, data);
					
					if (nitems [0] > 0) {
						byte [] buffer = new byte [nitems [0]];
						OS.memmove(buffer, data [0], buffer.length);
						OS.XFree (data [0]);
						char[] chars = Converter.mbcsToWcs(null, buffer);
						String string = new String (chars);
						
						int lastIndex = 0;
						int index = string.indexOf (':');
						while (index != -1) {
							String file = string.substring (lastIndex, index);
							Event event = new Event ();
							event.text = file;
							sendEvent (SWT.OpenDocument, event);
							lastIndex = index+1;
							index = string.indexOf(':', lastIndex);
						}
					}
				}
			}
			break;
	}
	return 0;
}

/**
 * Causes the <code>run()</code> method of the runnable to
 * be invoked by the user-interface thread at the next 
 * reasonable opportunity. The thread which calls this method
 * is suspended until the runnable completes.  Specifying <code>null</code>
 * as the runnable simply wakes the user-interface thread.
 * <p>
 * Note that at the time the runnable is invoked, widgets 
 * that have the receiver as their display may have been
 * disposed. Therefore, it is necessary to check for this
 * case inside the runnable before accessing the widget.
 * </p>
 * 
 * @param runnable code to run on the user-interface thread or <code>null</code>
 *
 * @exception SWTException <ul>
 *    <li>ERROR_FAILED_EXEC - if an exception occurred when executing the runnable</li>
 *    <li>ERROR_DEVICE_DISPOSED - if the receiver has been disposed</li>
 * </ul>
 *
 * @see #asyncExec
 */
public void syncExec (Runnable runnable) {
	Synchronizer synchronizer;
	synchronized (Device.class) {
		if (isDisposed ()) error (SWT.ERROR_DEVICE_DISPOSED);
		synchronizer = this.synchronizer;
		synchronized (idleLock) {
			if (idleNeeded && idleHandle == 0) {
				//NOTE: calling unlocked function in OS
				idleHandle = OS._g_idle_add (idleProc, 0);
			}
		}
	}
	synchronizer.syncExec (runnable);
}

static int translateKey (int key) {
	for (int i=0; i<KeyTable.length; i++) {
		if (KeyTable [i] [0] == key) return KeyTable [i] [1];
	}
	return 0;
}

static int untranslateKey (int key) {
	for (int i=0; i<KeyTable.length; i++) {
		if (KeyTable [i] [1] == key) return KeyTable [i] [0];
	}
	return 0;
}

/**
 * Forces all outstanding paint requests for the display
 * to be processed before this method returns.
 *
 * @exception SWTException <ul>
 *    <li>ERROR_THREAD_INVALID_ACCESS - if not called from the thread that created the receiver</li>
 *    <li>ERROR_DEVICE_DISPOSED - if the receiver has been disposed</li>
 * </ul>
 * 
 * @see Control#update()
 */
public void update () {
	checkDevice ();
	flushExposes (0, true);
	OS.gdk_window_process_all_updates ();
}

/**
 * If the receiver's user-interface thread was <code>sleep</code>ing, 
 * causes it to be awakened and start running again. Note that this
 * method may be called from any thread.
 * 
 * @exception SWTException <ul>
 *    <li>ERROR_DEVICE_DISPOSED - if the receiver has been disposed</li>
 * </ul>
 * 
 * @see #sleep
 */
public void wake () {
	synchronized (Device.class) {
		if (isDisposed ()) error (SWT.ERROR_DEVICE_DISPOSED);
		if (thread == Thread.currentThread ()) return;
		wakeThread ();
	}
}

void wakeThread () {
	OS.g_main_context_wakeup (0);
	wake = true;
}

static char wcsToMbcs (char ch) {
	int key = ch & 0xFFFF;
	if (key <= 0x7F) return ch;
	byte [] buffer = Converter.wcsToMbcs (null, new char [] {ch}, false);
	if (buffer.length == 1) return (char) buffer [0];
	if (buffer.length == 2) {
		return (char) (((buffer [0] & 0xFF) << 8) | (buffer [1] & 0xFF));
	}
	return 0;
}

long /*int*/ windowProc (long /*int*/ handle, long /*int*/ user_data) {
	Widget widget = getWidget (handle);
	if (widget == null) return 0;
	return widget.windowProc (handle, user_data);
}

long /*int*/ windowProc (long /*int*/ handle, long /*int*/ arg0, long /*int*/ user_data) {
	Widget widget = getWidget (handle);
	if (widget == null) return 0;
	return widget.windowProc (handle, arg0, user_data);
}

long /*int*/ windowProc (long /*int*/ handle, long /*int*/ arg0, long /*int*/ arg1, long /*int*/ user_data) {
	Widget widget = getWidget (handle);
	if (widget == null) return 0;
	return widget.windowProc (handle, arg0, arg1, user_data);
}

long /*int*/ windowProc (long /*int*/ handle, long /*int*/ arg0, long /*int*/ arg1, long /*int*/ arg2, long /*int*/ user_data) {
	Widget widget = getWidget (handle);
	if (widget == null) return 0;
	return widget.windowProc (handle, arg0, arg1, arg2, user_data);
}

long /*int*/ windowTimerProc (long /*int*/ handle) {
	Widget widget = getWidget (handle);
	if (widget == null) return 0;
	return widget.timerProc (handle);
}

long /*int*/ gdk_window_get_device_position (long /*int*/ window, int[] x, int[] y, int[] mask) {
	if (OS.GTK3) {
		long /*int*/ display = 0;
		if( window != 0) {
			display = OS.gdk_window_get_display (window);
		} else {
			window = OS.gdk_get_default_root_window ();
			display = OS.gdk_window_get_display (window);
		}
		long /*int*/ device_manager = OS.gdk_display_get_device_manager (display);
		long /*int*/ pointer = OS.gdk_device_manager_get_client_pointer (device_manager);
		return OS.gdk_window_get_device_position(window, pointer, x, y, mask);
	} else {
		return OS.gdk_window_get_pointer (window, x, y, mask);
	}
}

long /*int*/ gdk_device_get_window_at_position (int[] win_x, int[] win_y) {
	if (OS.GTK3) {
		long /*int*/ display = OS.gdk_display_get_default ();
		long /*int*/ device_manager = OS.gdk_display_get_device_manager (display);
		long /*int*/ device = OS.gdk_device_manager_get_client_pointer (device_manager);
		return OS.gdk_device_get_window_at_position (device, win_x, win_y);
	} else {
		return OS.gdk_window_at_pointer (win_x, win_y);
	}
}

}
