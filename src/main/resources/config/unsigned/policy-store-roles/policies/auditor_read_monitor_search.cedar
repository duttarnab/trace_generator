@id("auditor_read_monitor_search")
permit(principal is Jans::User, action in [Jans::Action::"Read", Jans::Action::"Monitor", Jans::Action::"Search"], resource)
when { principal.role.contains("Auditor") };
