@id("security_officer_monitor")
permit(principal is Jans::User, action in [Jans::Action::"Monitor", Jans::Action::"Search", Jans::Action::"Read", Jans::Action::"Tag"], resource)
when { principal.role.contains("SecurityOfficer") };
