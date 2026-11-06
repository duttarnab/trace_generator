@id("manager_app_access")
permit(principal is Jans::User, action in [Jans::Action::"Read", Jans::Action::"Write", Jans::Action::"Share", Jans::Action::"Monitor", Jans::Action::"Compare"], resource is Jans::Application)
when { principal.role.contains("Manager") };
