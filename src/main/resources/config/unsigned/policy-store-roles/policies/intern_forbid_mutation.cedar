@id("intern_forbid_mutation")
forbid(principal is Jans::User, action in [Jans::Action::"Write", Jans::Action::"Share", Jans::Action::"Execute"], resource)
when { principal.role.contains("Intern") };
