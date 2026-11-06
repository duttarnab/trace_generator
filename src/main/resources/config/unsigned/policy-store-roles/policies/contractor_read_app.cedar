@id("contractor_read_app")
permit(principal is Jans::User, action in [Jans::Action::"Read", Jans::Action::"Execute"], resource is Jans::Application)
when { principal.role.contains("Contractor") };
