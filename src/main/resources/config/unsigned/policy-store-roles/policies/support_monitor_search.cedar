@id("support_monitor_search")
permit(principal is Jans::User, action in [Jans::Action::"Monitor", Jans::Action::"Search", Jans::Action::"Read"], resource is Jans::Application)
when { principal.role.contains("Support") };
