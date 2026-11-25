package ai.crystalgram.holo;

import android.app.Activity;
import android.app.AlertDialog;

import org.drinkless.tdlib.TdApi;

final class PrivacySettings {
    private PrivacySettings() {}

    static void show(Activity activity) {
        String[] labels = {"Last seen & online", "Profile photos", "Phone number",
                "Forwarded-message link", "Group invitations", "Calls", "Active sessions"};
        TdApi.UserPrivacySetting[] settings = {
                new TdApi.UserPrivacySettingShowStatus(), new TdApi.UserPrivacySettingShowProfilePhoto(),
                new TdApi.UserPrivacySettingShowPhoneNumber(), new TdApi.UserPrivacySettingShowLinkInForwardedMessages(),
                new TdApi.UserPrivacySettingAllowChatInvites(), new TdApi.UserPrivacySettingAllowCalls()
        };
        new AlertDialog.Builder(activity).setTitle("Privacy and security").setItems(labels, (d, i) -> {
            if (i == settings.length) sessions(activity);
            else rules(activity, labels[i], settings[i]);
        }).show();
    }

    private interface RulesHandler {
        void accept(TdApi.UserPrivacySettingRule[] rules);
    }

    private static void fetchRules(Activity activity, TdApi.UserPrivacySetting setting, RulesHandler handler) {
        Td.send(new TdApi.GetUserPrivacySettingRules(setting), result -> activity.runOnUiThread(() -> {
            if (ready(activity, result) && result instanceof TdApi.UserPrivacySettingRules) {
                TdApi.UserPrivacySettingRule[] list = ((TdApi.UserPrivacySettingRules) result).rules;
                handler.accept(list == null ? new TdApi.UserPrivacySettingRule[0] : list);
            }
        }));
    }

    private static void rules(Activity activity, String title, TdApi.UserPrivacySetting setting) {
        fetchRules(activity, setting, current -> {
            String[] policies = {"Everybody", "My contacts", "Nobody"};
            int selected = PrivacyRules.policy(current);
            String[] labels = {
                    "Default: " + (selected < 0 ? "Custom" : policies[selected]),
                    "Exceptions"
            };
            new AlertDialog.Builder(activity).setTitle(title).setItems(labels, (d, index) -> {
                if (index == 1) showExceptions(activity, title, setting);
                else new AlertDialog.Builder(activity).setTitle("Default privacy rule")
                        .setSingleChoiceItems(policies, selected, (dialog, choice) -> {
                            dialog.dismiss();
                            if (selected == choice) {
                                rules(activity, title, setting);
                                return;
                            }
                            ConversationActions.confirm(activity, "Set default to “" + policies[choice] + "”?", () ->
                                    fetchRules(activity, setting, latest -> saveRules(activity, setting,
                                            PrivacyRules.withPolicy(latest, choice),
                                            () -> rules(activity, title, setting))));
                        }).setNegativeButton(android.R.string.cancel, null).show();
            }).setNegativeButton(android.R.string.cancel, null).show();
        });
    }

    private static void showExceptions(Activity activity, String title, TdApi.UserPrivacySetting setting) {
        fetchRules(activity, setting, current -> {
            String[] labels = {
                    exceptionLabel(current, true),
                    exceptionLabel(current, false)
            };
            new AlertDialog.Builder(activity).setTitle("Exceptions").setItems(labels, (d, index) -> {
                boolean allow = index == 0;
                String action = allow ? "Always Allow" : "Never Allow";
                new AlertDialog.Builder(activity).setTitle(action)
                        .setItems(new String[] {"People", "Group members"}, (types, type) ->
                                chooseExceptions(activity, title, setting, allow, type == 1, current))
                        .setNegativeButton(android.R.string.cancel, null).show();
            }).setNegativeButton(android.R.string.cancel, null).show();
        });
    }

    private static String exceptionLabel(TdApi.UserPrivacySettingRule[] rules, boolean allow) {
        int people = PrivacyRules.exceptions(rules, allow, false).size();
        int groups = PrivacyRules.exceptions(rules, allow, true).size();
        return (allow ? "Always Allow" : "Never Allow") + " · " + people + " people, " + groups + " groups";
    }

    private static void chooseExceptions(Activity activity, String title, TdApi.UserPrivacySetting setting,
                                         boolean allow, boolean groups, TdApi.UserPrivacySettingRule[] current) {
        java.util.Set<Long> before = PrivacyRules.exceptions(current, allow, groups);
        java.util.Map<Long, String> candidates = new java.util.LinkedHashMap<>();
        for (long id : before) {
            TdApi.Chat chat = groups ? Td.chat(id) : null;
            String name = groups ? (chat == null || Td.scope(chat) == ChatScope.EXCLUDED ? "" : Td.chatTitle(chat))
                    : Td.userName(Td.user(id));
            putCandidate(candidates, id, name, groups);
        }
        for (TdApi.Chat chat : Td.privacyChats()) {
            if (groups && (chat.type instanceof TdApi.ChatTypeBasicGroup || chat.type instanceof TdApi.ChatTypeSupergroup)) {
                putCandidate(candidates, chat.id, Td.chatTitle(chat), true);
            } else if (!groups && chat.type instanceof TdApi.ChatTypePrivate) {
                long id = ((TdApi.ChatTypePrivate) chat.type).userId;
                addPerson(candidates, id, Td.chatTitle(chat));
            }
        }
        if (groups) {
            showPicker(activity, title, setting, allow, true, before, candidates);
        } else Td.send(new TdApi.GetContacts(), result -> activity.runOnUiThread(() -> {
            if (!ConversationActions.active(activity) || !Td.authorized()) return;
            if (result instanceof TdApi.Users) {
                for (long id : ((TdApi.Users) result).userIds) addPerson(candidates, id, Td.userName(Td.user(id)));
            } else if (result instanceof TdApi.Error) {
                ConversationActions.toast(activity, ((TdApi.Error) result).message);
            }
            showPicker(activity, title, setting, allow, false, before, candidates);
        }));
    }

    private static void addPerson(java.util.Map<Long, String> choices, long id, String fallback) {
        TdApi.User user = Td.user(id);
        if (id == Td.me() || (user != null && (user.type instanceof TdApi.UserTypeDeleted
                || user.type instanceof TdApi.UserTypeBot))) return;
        String name = Td.userName(user);
        putCandidate(choices, id, name.isEmpty() ? fallback : name, false);
    }

    private static void putCandidate(java.util.Map<Long, String> choices, long id, String name, boolean groups) {
        choices.put(id, name.isEmpty() ? (groups ? "Group " : "User ") + id : name);
    }

    private static void showPicker(Activity activity, String title, TdApi.UserPrivacySetting setting,
                                   boolean allow, boolean groups, java.util.Set<Long> before,
                                   java.util.Map<Long, String> candidates) {
        if (candidates.isEmpty()) {
            ConversationActions.toast(activity, groups ? "No supported groups are available." : "No people are available.");
            showExceptions(activity, title, setting);
            return;
        }
        java.util.ArrayList<Long> ids = new java.util.ArrayList<>(candidates.keySet());
        java.util.Collections.sort(ids, (a, b) -> candidates.get(a).compareToIgnoreCase(candidates.get(b)));
        String[] labels = new String[ids.size()];
        boolean[] checked = new boolean[ids.size()];
        java.util.Set<Long> selected = new java.util.LinkedHashSet<>(before);
        for (int i = 0; i < ids.size(); i++) {
            labels[i] = candidates.get(ids.get(i));
            checked[i] = before.contains(ids.get(i));
        }
        new AlertDialog.Builder(activity)
                .setTitle((allow ? "Always Allow" : "Never Allow") + (groups ? " · Group members" : " · People"))
                .setMultiChoiceItems(labels, checked, (d, i, enabled) -> {
                    if (enabled) selected.add(ids.get(i));
                    else selected.remove(ids.get(i));
                })
                .setPositiveButton("Save", (d, w) -> {
                    if (selected.equals(before)) {
                        showExceptions(activity, title, setting);
                        return;
                    }
                    fetchRules(activity, setting, latest -> saveRules(activity, setting,
                            PrivacyRules.editExceptions(latest, allow, groups, before, selected),
                            () -> showExceptions(activity, title, setting)));
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private static void saveRules(Activity activity, TdApi.UserPrivacySetting setting,
                                  TdApi.UserPrivacySettingRule[] rules, Runnable saved) {
        Td.send(new TdApi.SetUserPrivacySettingRules(setting, new TdApi.UserPrivacySettingRules(rules)),
                result -> activity.runOnUiThread(() -> {
                    if (ready(activity, result) && result instanceof TdApi.Ok) saved.run();
                }));
    }

    private static void sessions(Activity activity) {
        Td.send(new TdApi.GetActiveSessions(), result -> activity.runOnUiThread(() -> {
            if (!ready(activity, result) || !(result instanceof TdApi.Sessions)) return;
            TdApi.Session[] sessions = ((TdApi.Sessions) result).sessions;
            String[] labels = new String[sessions.length];
            boolean others = false;
            for (int i = 0; i < sessions.length; i++) {
                TdApi.Session session = sessions[i];
                others |= !session.isCurrent;
                labels[i] = session.applicationName + " · " + session.deviceModel
                        + (session.isCurrent ? "\nThis device" : "\n" + session.location);
            }
            AlertDialog.Builder dialog = new AlertDialog.Builder(activity).setTitle("Active sessions")
                    .setItems(labels, (d, index) -> sessionDetails(activity, sessions[index]))
                    .setNegativeButton(android.R.string.cancel, null);
            if (others) dialog.setNeutralButton("End other sessions", (d, w) ->
                    ConversationActions.confirm(activity, "End all other sessions?", () ->
                            end(activity, new TdApi.TerminateAllOtherSessions())));
            dialog.show();
        }));
    }

    private static void sessionDetails(Activity activity, TdApi.Session session) {
        long lastActive = session.lastActiveDate * 1000L;
        String details = session.applicationName + " " + session.applicationVersion + "\n" + session.deviceModel
                + " · " + session.platform + " " + session.systemVersion + "\n"
                + session.location + " · " + session.ipAddress
                + (lastActive > 0 ? "\nLast active " + android.text.format.DateUtils.getRelativeTimeSpanString(lastActive) : "");
        AlertDialog.Builder dialog = new AlertDialog.Builder(activity)
                .setTitle(session.isCurrent ? "This device" : "Session details").setMessage(details)
                .setNegativeButton(android.R.string.cancel, null);
        if (!session.isCurrent) dialog.setPositiveButton("End session", (d, w) ->
                ConversationActions.confirm(activity, "End this session?", () ->
                        end(activity, new TdApi.TerminateSession(session.id))));
        dialog.show();
    }

    private static void end(Activity activity, TdApi.Function<?> query) {
        if (!Td.authorized() || !ConversationActions.active(activity)) return;
        Td.send(query, result -> activity.runOnUiThread(() -> {
            if (ready(activity, result)) sessions(activity);
        }));
    }

    private static boolean ready(Activity activity, TdApi.Object result) {
        if (!ConversationActions.active(activity) || !Td.authorized()) return false;
        if (result instanceof TdApi.Error) {
            ConversationActions.toast(activity, ((TdApi.Error) result).message);
            return false;
        }
        return true;
    }
}
