/*
 * 
 */
ZmConferenceService = function(params) {
	if (arguments.length == 0) return;

	params.type = ZmOrganizer.CONFERENCE_ITEM;
	ZmOrganizer.call(this, params);

	// Add a child that says "loading..." which will get replaced by real items
	// after this item is expanded. 
	var loadingArgs = {
		tree: this.tree,
		name: ZmMsg.loading,
		parent: this,
		type: ZmOrganizer.CONFERENCE_ITEM
	};
	var loading = new ZmOrganizer(loadingArgs);
	this.children.add(loading);
	this.roomsLoaded = false;
};

ZmConferenceService.prototype = new ZmOrganizer;
ZmConferenceService.prototype.constructor = ZmConferenceService;

ZmConferenceService.prototype.toString =
function() {
	return "ZmConferenceService";
};

ZmConferenceService.sortCompare =
function(a, b) {
	if (a.name.toLowerCase() > b.name.toLowerCase()) { return 1; }
	if (a.name.toLowerCase() < b.name.toLowerCase()) { return -1; }
	return 0;
};


ZmConferenceService.prototype.getAddress =
function() {
	return this.id;
};

ZmConferenceService.prototype.getIcon =
function() {
	return "Globe";
};

ZmConferenceService.prototype.getRooms =
function(callback, force) {
	if (!this.roomsLoaded || force) {
		ZmImApp.INSTANCE.getService().getConferenceRooms(this, new AjxCallback(this, this._handleResponseGetRooms, [callback]));
	}
};

ZmConferenceService.prototype._handleResponseGetRooms =
function(callback, rooms) {
	while (this.children.size()) {
		var child = this.children.get(this.children.size() - 1);
		child.notifyDelete();
	}
	for (var i = 0, count = rooms.length; i < count; i++) {
		var roomArgs = {
			tree: this.tree,
			id: rooms[i].addr,
			name: rooms[i].name,
			parent: this };
		var room = new ZmConferenceRoom(roomArgs);
		this.children.add(room);
		room._notify(ZmEvent.E_CREATE);
	}
	this.roomsLoaded = true;
	if (callback) {
		callback.run(this.children);
	}
};

ZmConferenceService.prototype.createRoom =
function(name, callback) {
	ZmImApp.INSTANCE.getService().createConferenceRoom(this, name, new AjxCallback(this, this._handleResponseCreateRoom, [callback]));
};

ZmConferenceService.prototype._handleResponseCreateRoom =
function(callback, jsonObj) {
	var args = {
		id: jsonObj.addr,
		tree: this.tree,
		name: jsonObj.name,
		parent: this,
		status: jsonObj.status,
		thread: jsonObj.thread
	};
	var room = new ZmConferenceRoom(args);
	this.children.add(room);
	room._notify(ZmEvent.E_CREATE);
	if (callback) {
		callback.run(room);
	}
};

