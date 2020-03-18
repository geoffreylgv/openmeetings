/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License") +  you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.openmeetings.web.room.sidebar;

import static org.apache.openmeetings.web.app.Application.kickUser;
import static org.apache.openmeetings.web.util.CallbackFunctionHelper.getNamedFunction;
import static org.apache.wicket.ajax.attributes.CallbackParameter.explicit;

import org.apache.openmeetings.core.remote.StreamProcessor;
import org.apache.openmeetings.core.util.WebSocketHelper;
import org.apache.openmeetings.db.entity.basic.Client;
import org.apache.openmeetings.db.entity.room.Room;
import org.apache.openmeetings.db.entity.room.Room.Right;
import org.apache.openmeetings.db.entity.room.Room.RoomElement;
import org.apache.openmeetings.db.util.ws.RoomMessage;
import org.apache.openmeetings.db.util.ws.TextRoomMessage;
import org.apache.openmeetings.web.app.ClientManager;
import org.apache.openmeetings.web.app.WebSession;
import org.apache.openmeetings.web.common.NameDialog;
import org.apache.openmeetings.web.common.confirmation.ConfirmationDialog;
import org.apache.openmeetings.web.room.RoomPanel;
import org.apache.openmeetings.web.room.RoomPanel.Action;
import org.apache.openmeetings.web.room.VideoSettings;
import org.apache.openmeetings.web.room.activities.ActivitiesPanel;
import org.apache.openmeetings.web.room.activities.Activity;
import org.apache.openmeetings.web.util.ExtendedClientProperties;
import org.apache.wicket.ajax.AbstractDefaultAjaxBehavior;
import org.apache.wicket.ajax.AjaxRequestTarget;
import org.apache.wicket.core.request.handler.IPartialPageRequestHandler;
import org.apache.wicket.markup.head.IHeaderResponse;
import org.apache.wicket.markup.head.PriorityHeaderItem;
import org.apache.wicket.markup.html.WebMarkupContainer;
import org.apache.wicket.markup.html.panel.Panel;
import org.apache.wicket.model.ResourceModel;
import org.apache.wicket.spring.injection.annot.SpringBean;
import org.apache.wicket.util.string.StringValue;
import org.apache.wicket.util.string.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.openjson.JSONObject;

public class RoomSidebar extends Panel {
	private static final long serialVersionUID = 1L;
	private static final Logger log = LoggerFactory.getLogger(RoomSidebar.class);
	public static final String FUNC_SETTINGS = "avSettings";
	public static final String PARAM_ACTION = "action";
	public static final String PARAM_RIGHT = "right";
	public static final String PARAM_UID = "uid";
	public static final String PARAM_SETTINGS = "s";
	private final RoomPanel room;
	private UploadDialog upload;
	private RoomFilePanel roomFiles;
	private final WebMarkupContainer fileTab = new WebMarkupContainer("file-tab");
	private ConfirmationDialog confirmKick;
	private boolean showFiles;
	private boolean avInited = false;
	private Client kickedClient;
	private VideoSettings settings = new VideoSettings("settings");
	private ActivitiesPanel activities;
	private final AbstractDefaultAjaxBehavior avSettings = new AbstractDefaultAjaxBehavior() {
		private static final long serialVersionUID = 1L;

		@Override
		protected void respond(AjaxRequestTarget target) {
			StringValue s = getRequest().getRequestParameters().getParameterValue(PARAM_SETTINGS);
			if (!s.isEmpty()) {
				ExtendedClientProperties cp = WebSession.get().getExtendedProperties();
				Client c = room.getClient();
				cp.setSettings(new JSONObject(s.toString())).update(c);
				if (!avInited) {
					avInited = true;
					if (Room.Type.CONFERENCE == room.getRoom().getType()) {
						streamProcessor.toggleActivity(c, Client.Activity.AUDIO_VIDEO);
					}
				}
				cm.update(c);
				room.broadcast(c);
			}
		}
	};

	@SpringBean
	private ClientManager cm;
	@SpringBean
	private StreamProcessor streamProcessor;

	public RoomSidebar(String id, final RoomPanel room) {
		super(id);
		this.room = room;
	}

	@Override
	protected void onInitialize() {
		super.onInitialize();
		final NameDialog addFolder = new NameDialog("addFolder", getString("712")) {
			private static final long serialVersionUID = 1L;

			@Override
			protected void onSubmit(AjaxRequestTarget target) {
				roomFiles.createFolder(target, getModelObject());
				super.onSubmit(target);
			}
		};
		roomFiles = new RoomFilePanel("tree", room, addFolder);
		add(fileTab.setVisible(!room.isInterview()), roomFiles.setVisible(!room.isInterview()));

		add(addFolder, settings);
		add(avSettings);
		add(confirmKick = new ConfirmationDialog("confirm-kick", new ResourceModel("603"), new ResourceModel("605")) {
			private static final long serialVersionUID = 1L;

			@Override
			protected void onConfirm(AjaxRequestTarget target) {
				kickUser(kickedClient);
			}
		});
		add(upload = new UploadDialog("upload", room, roomFiles));
		updateShowFiles(null);
		add(activities = new ActivitiesPanel("activities", room));
	}

	@Override
	public void renderHead(IHeaderResponse response) {
		super.renderHead(response);
		response.render(new PriorityHeaderItem(getNamedFunction(FUNC_SETTINGS, avSettings, explicit(PARAM_SETTINGS))));
	}

	private void updateShowFiles(IPartialPageRequestHandler handler) {
		if (room.isInterview()) {
			return;
		}
		showFiles = !room.getRoom().isHidden(RoomElement.FILES) && room.getClient().hasRight(Right.PRESENTER);
		roomFiles.setReadOnly(!showFiles, handler);
	}

	public void update(IPartialPageRequestHandler handler) {
		if (room.getRoom() == null || room.getClient() == null) {
			return;
		}
		updateShowFiles(handler);
	}

	public void updateFiles(IPartialPageRequestHandler handler) {
		roomFiles.update(handler);
	}

	public RoomFilePanel getFilesPanel() {
		return roomFiles;
	}

	public boolean isShowFiles() {
		return showFiles;
	}

	public void showUpload(IPartialPageRequestHandler handler) {
		upload.show(handler);
	}

	public void setFilesActive(IPartialPageRequestHandler handler) {
		handler.appendJavaScript("$('#room-sidebar-files-tab').tab('show');");
	}

	public void addActivity(Activity a, IPartialPageRequestHandler handler) {
		activities.add(a, handler);
	}

	public void removeActivity(String uid, IPartialPageRequestHandler handler) {
		activities.remove(handler, uid);
	}

	public void roomAction(IPartialPageRequestHandler handler, JSONObject o) {
		try {
			final String uid = o.getString(PARAM_UID);
			if (Strings.isEmpty(uid)) {
				return;
			}
			Client self = room.getClient();
			Action a = Action.valueOf(o.getString(PARAM_ACTION));
			switch (a) {
				case kick:
					if (self.hasRight(Right.MODERATOR)) {
						kickedClient = cm.get(uid);
						if (kickedClient == null) {
							return;
						}
						if (!kickedClient.hasRight(Right.SUPER_MODERATOR) && !self.getUid().equals(kickedClient.getUid())) {
							confirmKick.show(handler);
						}
					}
					break;
				case muteOthers:
					if (room.getClient().hasRight(Right.MUTE_OTHERS)) {
						WebSocketHelper.sendRoom(new TextRoomMessage(room.getRoom().getId(), self, RoomMessage.Type.MUTE_OTHERS, uid));
					}
					break;
				case mute:
				{
					Client c = cm.get(uid);
					if (c == null || !c.hasActivity(Client.Activity.AUDIO)) {
						return;
					}
					if (self.hasRight(Right.MODERATOR) || self.getUid().equals(c.getUid())) {
						WebSocketHelper.sendRoom(new TextRoomMessage(room.getRoom().getId(), self, RoomMessage.Type.MUTE
								, new JSONObject()
										.put("sid", self.getSid())
										.put(PARAM_UID, uid)
										.put("mute", o.getBoolean("mute")).toString()));
					}
				}
					break;
				case toggleRight:
					toggleRight(handler, self, uid, o);
					break;
				default:
			}
		} catch (Exception e) {
			log.error("Unexpected exception while toggle 'roomAction'", e);
		}
	}

	private void toggleRight(IPartialPageRequestHandler handler, Client self, String uid, JSONObject o) {
		try {
			Right right = Right.valueOf(o.getString(PARAM_RIGHT));
			if (self.hasRight(Right.MODERATOR)) {
				Client client = cm.get(uid);
				if (client == null) {
					return;
				}
				if (client.hasRight(right)) {
					room.denyRight(client, right);
				} else {
					if (Right.VIDEO == right) {
						room.allowRight(client, Right.AUDIO, right);
					} else {
						room.allowRight(client, right);
					}
				}
			} else {
				room.requestRight(right, handler);
			}
		} catch (Exception e) {
			log.error("Unexpected exception while toggle 'right'", e);
		}
	}
}
