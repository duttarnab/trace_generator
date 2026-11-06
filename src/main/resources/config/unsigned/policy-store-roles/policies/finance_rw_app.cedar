@id("finance_rw_app")
permit(principal is Jans::User, action in [Jans::Action::"Read", Jans::Action::"Write", Jans::Action::"Compare"], resource is Jans::Application)
when { principal.role.contains("Finance") };
