@id("intern_read_app")
permit(principal is Jans::User, action == Jans::Action::"Read", resource is Jans::Application)
when { principal.role.contains("Intern") };
