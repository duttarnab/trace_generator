@id("developer_execute_app")
permit(principal is Jans::User, action in [Jans::Action::"Read", Jans::Action::"Write", Jans::Action::"Execute", Jans::Action::"Monitor"], resource is Jans::Application)
when { principal.role.contains("Developer") };
