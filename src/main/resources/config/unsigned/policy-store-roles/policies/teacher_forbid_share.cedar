@id("teacher_forbid_share")
forbid(principal is Jans::User, action == Jans::Action::"Share", resource)
when { principal.role.contains("Teacher") };
