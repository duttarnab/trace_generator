@id("guest_forbid_all")
forbid(principal is Jans::User, action, resource)
when { principal.role.contains("Guest") };
