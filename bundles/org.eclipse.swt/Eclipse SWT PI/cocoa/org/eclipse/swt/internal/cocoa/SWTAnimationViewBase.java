/*******************************************************************************
 * Copyright (c) 2008 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.swt.internal.cocoa;

public class SWTAnimationViewBase extends NSView {
	
	public SWTAnimationViewBase() {
		super(0);
	}
		
	public SWTAnimationViewBase(int id) {
		super(id);
	}

	public int tag() {
		return OS.objc_msgSend(id, OS.sel_tag);
	}

	public void setTag(int tag) {
		OS.objc_msgSend(id, OS.sel_setTag_1, tag);
	}
	
	public float animationValue() {
		return OS.objc_msgSend(id, OS.sel_animationValue);
	}
	
	public void setAnimationValue(float val) {
		OS.objc_msgSend(id, OS.sel_setAnimationValue_1, val);
	}
	
	void animationUpdated(NSNumber val) {
	}
}
