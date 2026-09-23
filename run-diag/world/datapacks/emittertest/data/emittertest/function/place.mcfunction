say RELOADTEST-PLACING
setblock 0 150 0 minecraft:glowstone
lucistarlink light 0 150 0
save-all flush
schedule function emittertest:finish 3s
