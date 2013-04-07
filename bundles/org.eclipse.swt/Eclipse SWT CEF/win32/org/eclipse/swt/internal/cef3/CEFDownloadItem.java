package org.eclipse.swt.internal.cef3;

public class CEFDownloadItem extends CEFBase {
	cef_download_item_t struct;
	
	public CEFDownloadItem(long /*int*/ address) {
		super(address);
		struct = new cef_download_item_t();
		CEF3.memmove(struct, address, cef_download_item_t.sizeof);
	}
	
	public long /*int*/ getUrl() {
		return CEF3.callFuncPtr(struct.get_url, address);
	}
	
	public int getId() {
		return CEF3.callFuncInt(struct.get_id, address);
	}
	
	public long getTotalBytes() {
		return CEF3.callFuncLong(struct.get_total_bytes,address);
	}
	
	public long getReceivedBytes() {
		return CEF3.callFuncLong(struct.get_received_bytes,address);
	}
	
	public int isComplete() {
		return CEF3.callFuncInt(struct.is_complete, address);
	}
	
	public int isInProgress() {
		return CEF3.callFuncInt(struct.is_in_progress, address);
	}
	
	public int isCancelled() {
		return CEF3.callFuncInt(struct.is_canceled, address);
	}	

}