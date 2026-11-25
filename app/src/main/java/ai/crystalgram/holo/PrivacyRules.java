package ai.crystalgram.holo;

import org.drinkless.tdlib.TdApi;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;

final class PrivacyRules {
    private PrivacyRules() {}

    static int policy(TdApi.UserPrivacySettingRule[] rules) {
        for (TdApi.UserPrivacySettingRule rule : rules) {
            if (rule instanceof TdApi.UserPrivacySettingRuleAllowContacts) return 1;
            if (rule instanceof TdApi.UserPrivacySettingRuleAllowAll) return 0;
            if (rule instanceof TdApi.UserPrivacySettingRuleRestrictAll) return 2;
        }
        return -1;
    }

    static TdApi.UserPrivacySettingRule[] withPolicy(TdApi.UserPrivacySettingRule[] rules, int policy) {
        ArrayList<TdApi.UserPrivacySettingRule> updated = new ArrayList<>();
        for (TdApi.UserPrivacySettingRule rule : rules) {
            if (!(rule instanceof TdApi.UserPrivacySettingRuleAllowContacts)
                    && !(rule instanceof TdApi.UserPrivacySettingRuleAllowAll)
                    && !(rule instanceof TdApi.UserPrivacySettingRuleRestrictAll)) updated.add(rule);
        }
        if (policy == 1) updated.add(new TdApi.UserPrivacySettingRuleAllowContacts());
        updated.add(policy == 0 ? new TdApi.UserPrivacySettingRuleAllowAll()
                : new TdApi.UserPrivacySettingRuleRestrictAll());
        return updated.toArray(new TdApi.UserPrivacySettingRule[0]);
    }

    static Set<Long> exceptions(TdApi.UserPrivacySettingRule[] rules, boolean allow, boolean groups) {
        Set<Long> set = new LinkedHashSet<>();
        for (TdApi.UserPrivacySettingRule rule : rules) {
            long[] ids = ids(rule, allow, groups);
            if (ids != null) for (long id : ids) set.add(id);
        }
        return set;
    }

    static TdApi.UserPrivacySettingRule[] editExceptions(TdApi.UserPrivacySettingRule[] rules,
            boolean allow, boolean groups, Set<Long> before, Set<Long> selected) {
        Set<Long> added = new LinkedHashSet<>(selected);
        added.removeAll(before);
        Set<Long> removed = new LinkedHashSet<>(before);
        removed.removeAll(selected);
        ArrayList<TdApi.UserPrivacySettingRule> updated = new ArrayList<>();
        // A newly chosen explicit exception takes priority over an overlapping group or default rule.
        if (!added.isEmpty()) updated.add(makeRule(added, allow, groups));
        for (TdApi.UserPrivacySettingRule rule : rules) {
            long[] same = ids(rule, allow, groups);
            long[] opposite = ids(rule, !allow, groups);
            if (same == null && opposite == null) {
                updated.add(rule);
                continue;
            }
            Set<Long> remaining = new LinkedHashSet<>();
            for (long id : same != null ? same : opposite) {
                if (!added.contains(id) && (same == null || !removed.contains(id))) remaining.add(id);
            }
            if (!remaining.isEmpty()) updated.add(makeRule(remaining, same != null ? allow : !allow, groups));
        }
        return updated.toArray(new TdApi.UserPrivacySettingRule[0]);
    }

    private static long[] ids(TdApi.UserPrivacySettingRule rule, boolean allow, boolean groups) {
        if (groups) {
            if (allow && rule instanceof TdApi.UserPrivacySettingRuleAllowChatMembers)
                return ((TdApi.UserPrivacySettingRuleAllowChatMembers) rule).chatIds;
            if (!allow && rule instanceof TdApi.UserPrivacySettingRuleRestrictChatMembers)
                return ((TdApi.UserPrivacySettingRuleRestrictChatMembers) rule).chatIds;
        } else {
            if (allow && rule instanceof TdApi.UserPrivacySettingRuleAllowUsers)
                return ((TdApi.UserPrivacySettingRuleAllowUsers) rule).userIds;
            if (!allow && rule instanceof TdApi.UserPrivacySettingRuleRestrictUsers)
                return ((TdApi.UserPrivacySettingRuleRestrictUsers) rule).userIds;
        }
        return null;
    }

    private static TdApi.UserPrivacySettingRule makeRule(Set<Long> ids, boolean allow, boolean groups) {
        long[] values = new long[ids.size()];
        int i = 0;
        for (long id : ids) values[i++] = id;
        if (groups) return allow ? new TdApi.UserPrivacySettingRuleAllowChatMembers(values)
                : new TdApi.UserPrivacySettingRuleRestrictChatMembers(values);
        return allow ? new TdApi.UserPrivacySettingRuleAllowUsers(values)
                : new TdApi.UserPrivacySettingRuleRestrictUsers(values);
    }
}
