@id("forbid_locked")
forbid(principal, action, resource)
when { resource has locked && resource.locked };
