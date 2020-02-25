#!/bin/env lua5.3

-- lua5.3 dot.lua /tmp/freechains/8400/chains/ | dot -Tpng -o out.png

local json = require 'json'

DIR   = ...
CHAIN = json.decode(assert(io.open(DIR..'/chain')):read('*a'))
NODES = {}
CONNS = {}

function sub (str)
    return string.sub(str,1,9)
end

function out ()
    local f = io.stdout
    f:write([[
digraph graphname {
    rankdir=LR;  // Rank Direction Left to Right
    nodesep=1.0 // increases the separation between nodes
    edge [];
    //splines = true;
    ]]..table.concat(NODES,'\n    ')..[[

    ]]..table.concat(CONNS,'\n    ')..[[

}
]])
    f:close()
end

function go (hash)
    if NODES[hash] then
        return
    end
    NODES[hash] = true

    local blk = json.decode(assert(io.open(DIR..'/blocks/'..hash..'.blk')):read('*a'))

    local h   = blk.hashable
    local ref = sub(h.refs[1] or '')
    local t   = math.floor(h.time/3600000)

    NODES[#NODES+1] = '_'..hash..'[label="'..sub(hash)..'\n'..h.payload..'\n'..ref..'\n'..t..'"];'

    for _,front in ipairs(blk.fronts) do
        CONNS[#CONNS+1] = '_'..hash..' -> _'..front
        go(front)
    end
end

go('0_'..CHAIN.hash)
out()