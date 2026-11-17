package at.esoren.vystormworlds;

import Vystorm.vystorm_core.CoreApi;
import Vystorm.vystorm_core._uiMethods.admin.AdminAction;
import Vystorm.vystorm_core._uiMethods.admin.AdminModule;
import java.util.List;

/** The Vystorm Worlds module in Core's {@code /vadmin} dashboard. Loaded only after the capability check. */
final class AdminEntries {
    private AdminEntries() {}

    static void register(WorldsPlugin plugin) {
        var lang = plugin.lang();
        String perm = "vystormworlds.admin";
        plugin.core().admin().register(plugin, new AdminModule("worlds", lang.ref("admin-module"), plugin.getName(), "worlds", perm, "worlds admin",
                List.of(new AdminAction("list", lang.ref("admin-action-list"), perm, "list", "", "", false),
                        new AdminAction("info", lang.ref("admin-action-info"), perm, "info", "world", "world_nether", false),
                        new AdminAction("create", lang.ref("admin-action-create"), perm, "create", "", "", true),
                        new AdminAction("import", lang.ref("admin-action-import"), perm, "import", "", "", true),
                        new AdminAction("load", lang.ref("admin-action-load"), perm, "load", "world", "farmworld", true),
                        new AdminAction("unload", lang.ref("admin-action-unload"), perm, "unload", "world", "farmworld", true),
                        new AdminAction("groups", lang.ref("admin-action-groups"), perm, "groups", "", "", false),
                        new AdminAction("reload", lang.ref("admin-action-reload"), perm, "reload", "", "", true))));
    }
}
