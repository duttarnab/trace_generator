@id("forbid_public_network_non_admin")
forbid(principal is Jans::User, action, resource)
when { context has network_type && context.network_type == "public" }
unless { principal.role.contains("Admin") };
