@id("editor_rw_secret")
permit(principal is Jans::User, action in [Jans::Action::"Read", Jans::Action::"Write"], resource is Jans::SecretDocument)
when { principal.role.contains("Editor") };
