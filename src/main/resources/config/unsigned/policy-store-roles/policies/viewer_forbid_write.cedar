@id("viewer_forbid_write")
forbid(principal is Jans::User, action in [Jans::Action::"Write", Jans::Action::"Share", Jans::Action::"Execute", Jans::Action::"Tag"], resource)
when { principal.role.contains("Viewer") };
