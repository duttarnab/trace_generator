@id("editor_forbid_share_execute")
forbid(principal is Jans::User, action in [Jans::Action::"Share", Jans::Action::"Execute"], resource)
when { principal.role.contains("Editor") };
