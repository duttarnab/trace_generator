@id("viewer_read_app")
permit(principal is Jans::User, action in [Jans::Action::"Read", Jans::Action::"Search"], resource is Jans::Application)
when { principal.role.contains("Viewer") };
