@id("admin_full_access")
permit(principal is Jans::User, action, resource)
when { principal.role.contains("Admin") };
