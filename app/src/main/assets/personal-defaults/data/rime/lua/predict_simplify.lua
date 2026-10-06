-- SPDX-License-Identifier: LGPL-2.1-or-later
local filter = {}
function filter.init(env)
  env.converter = Opencc("t2s.json")
end
function filter.func(input, env)
  for candidate in input:iter() do
    if candidate.type == "prediction" and not env.engine.context:get_option("traditionalization") then
      yield(ShadowCandidate(candidate, "prediction", env.converter:convert(candidate.text), candidate.comment))
    else
      yield(candidate)
    end
  end
end
return filter
