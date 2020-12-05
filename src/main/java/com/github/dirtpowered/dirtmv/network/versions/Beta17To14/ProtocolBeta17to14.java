/*
 * Copyright (c) 2020 Dirt Powered
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.github.dirtpowered.dirtmv.network.versions.Beta17To14;

import com.github.dirtpowered.dirtmv.data.MinecraftVersion;
import com.github.dirtpowered.dirtmv.data.protocol.PacketData;
import com.github.dirtpowered.dirtmv.data.protocol.Type;
import com.github.dirtpowered.dirtmv.data.protocol.TypeHolder;
import com.github.dirtpowered.dirtmv.data.protocol.objects.BlockLocation;
import com.github.dirtpowered.dirtmv.data.protocol.objects.V1_3BChunk;
import com.github.dirtpowered.dirtmv.data.translator.PacketDirection;
import com.github.dirtpowered.dirtmv.data.translator.PacketTranslator;
import com.github.dirtpowered.dirtmv.data.translator.ServerProtocol;
import com.github.dirtpowered.dirtmv.data.user.UserData;
import com.github.dirtpowered.dirtmv.data.utils.ChatUtils;
import com.github.dirtpowered.dirtmv.data.utils.PacketUtil;
import com.github.dirtpowered.dirtmv.data.utils.StringUtils;
import com.github.dirtpowered.dirtmv.network.server.ServerSession;
import com.github.dirtpowered.dirtmv.network.versions.Beta17To14.block.RotationUtil;
import com.github.dirtpowered.dirtmv.network.versions.Beta17To14.block.SolidBlockList;
import com.github.dirtpowered.dirtmv.network.versions.Beta17To14.storage.BlockStorage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class ProtocolBeta17to14 extends ServerProtocol {

    public ProtocolBeta17to14() {
        super(MinecraftVersion.B1_8_1, MinecraftVersion.B1_7_3);
    }

    @Override
    public void registerTranslators() {

        // ping request
        addTranslator(0xFE, PacketDirection.CLIENT_TO_SERVER, new PacketTranslator() {

            @Override
            public PacketData translate(ServerSession session, PacketData data) throws IOException {
                String message = session.getMain().getConfiguration().preReleaseMOTD();
                message = ChatUtils.stripColor(message);

                int max = session.getMain().getConfiguration().getMaxOnline();
                int online = session.getConnectionCount();

                PacketData packetData = PacketUtil.createPacket(0xFF, new TypeHolder[]{
                        set(Type.STRING, message + "§" + online + "§" + max)
                });

                session.sendPacket(packetData, PacketDirection.SERVER_TO_CLIENT, getFrom());
                return new PacketData(-1); // cancel sending
            }
        });

        // login
        addTranslator(0x01, PacketDirection.CLIENT_TO_SERVER, new PacketTranslator() {

            @Override
            public PacketData translate(ServerSession session, PacketData data) {
                session.getUserData().setUsername(data.read(Type.STRING, 1));
                session.getUserData().getProtocolStorage().set(BlockStorage.class, new BlockStorage());

                return PacketUtil.createPacket(0x01, new TypeHolder[]{
                        set(Type.INT, 14), // INT
                        data.read(1), // STRING
                        data.read(2), // LONG
                        data.read(4) // BYTE
                });
            }
        });

        // login
        addTranslator(0x01, PacketDirection.SERVER_TO_CLIENT, new PacketTranslator() {

            @Override
            public PacketData translate(ServerSession session, PacketData data) throws IOException {
                UserData userData = session.getUserData();
                userData.getProtocolStorage().set(PlayerTabListCache.class, new PlayerTabListCache());

                // add default tab entry
                String username = userData.getUsername();
                String colored = StringUtils.safeSubstring("§6" + username, 0, 16);

                session.sendPacket(createTabEntryPacket(colored, true), PacketDirection.SERVER_TO_CLIENT, getFrom());

                int max = session.getMain().getConfiguration().getMaxOnline();

                return PacketUtil.createPacket(0x01, new TypeHolder[]{
                        data.read(0), // INT - entityId
                        data.read(1), // STRING - empty
                        data.read(2), // LONG - world seed
                        set(Type.INT, 0), // INT - gameMode
                        data.read(3), // BYTE - dimension
                        set(Type.BYTE, 1), // BYTE - difficulty
                        set(Type.BYTE, -128), // BYTE - world height
                        set(Type.BYTE, (byte) max), // BYTE - maxPlayers
                });
            }
        });

        // update health
        addTranslator(0x08, PacketDirection.SERVER_TO_CLIENT, new PacketTranslator() {

            @Override
            public PacketData translate(ServerSession session, PacketData data) {

                return PacketUtil.createPacket(0x08, new TypeHolder[]{
                        data.read(0),
                        set(Type.SHORT, (short) 6),
                        set(Type.FLOAT, 0.0F),

                });
            }
        });

        // respawn
        addTranslator(0x09, PacketDirection.CLIENT_TO_SERVER, new PacketTranslator() {

            @Override
            public PacketData translate(ServerSession session, PacketData data) {
                return PacketUtil.createPacket(0x09, new TypeHolder[]{
                        data.read(0),
                });
            }
        });

        // respawn
        addTranslator(0x09, PacketDirection.SERVER_TO_CLIENT, new PacketTranslator() {

            @Override
            public PacketData translate(ServerSession session, PacketData data) {
                return PacketUtil.createPacket(0x09, new TypeHolder[]{
                        data.read(0),
                        set(Type.BYTE, 0),
                        set(Type.BYTE, 0),
                        set(Type.SHORT, 128),
                        set(Type.LONG, 0),
                });
            }
        });

        // open window
        addTranslator(0x64, PacketDirection.SERVER_TO_CLIENT, new PacketTranslator() {

            @Override
            public PacketData translate(ServerSession session, PacketData data) {

                return PacketUtil.createPacket(0x64, new TypeHolder[]{
                        data.read(0),
                        data.read(1),
                        set(Type.STRING, data.read(Type.STRING, 2)),
                        data.read(3)
                });
            }
        });

        // game state
        addTranslator(0x46, PacketDirection.SERVER_TO_CLIENT, new PacketTranslator() {

            @Override
            public PacketData translate(ServerSession session, PacketData data) {

                return PacketUtil.createPacket(0x46, new TypeHolder[]{
                        data.read(0),
                        set(Type.BYTE, (byte) 0)
                });
            }
        });

        // entity action
        addTranslator(0x13, PacketDirection.CLIENT_TO_SERVER, new PacketTranslator() {

            @Override
            public PacketData translate(ServerSession session, PacketData data) {
                byte state = data.read(Type.BYTE, 1);

                if (state == 5 || state == 4) { // sprinting (stop/start)
                    return new PacketData(-1); // cancel sending
                }

                return data;
            }
        });

        // named entity spawn
        addTranslator(0x14, PacketDirection.SERVER_TO_CLIENT, new PacketTranslator() {

            @Override
            public PacketData translate(ServerSession session, PacketData data) throws IOException {
                int entityId = data.read(Type.INT, 0);
                String username = data.read(Type.STRING, 1);

                PlayerTabListCache cache = session.getUserData().getProtocolStorage().get(PlayerTabListCache.class);
                if (cache != null) {
                    session.sendPacket(createTabEntryPacket(username, true), PacketDirection.SERVER_TO_CLIENT, getFrom());
                    cache.getTabPlayers().put(entityId, username);
                }
                return data;
            }
        });

        // entity destroy
        addTranslator(0x1D, PacketDirection.SERVER_TO_CLIENT, new PacketTranslator() {

            @Override
            public PacketData translate(ServerSession session, PacketData data) throws IOException {
                int entityId = data.read(Type.INT, 0);

                PlayerTabListCache cache = session.getUserData().getProtocolStorage().get(PlayerTabListCache.class);

                if (cache != null && cache.getTabPlayers().containsKey(entityId)) {
                    String username = cache.getTabPlayers().get(entityId);

                    session.sendPacket(createTabEntryPacket(username, false), PacketDirection.SERVER_TO_CLIENT, getFrom());
                    cache.getTabPlayers().remove(entityId);
                }
                return data;
            }
        });

        // block change
        addTranslator(0x35, PacketDirection.SERVER_TO_CLIENT, new PacketTranslator() {

            @Override
            public PacketData translate(ServerSession session, PacketData data) {
                int x = data.read(Type.INT, 0);
                byte y = data.read(Type.BYTE, 1);
                int z = data.read(Type.INT, 2);
                byte blockId = data.read(Type.BYTE, 3);
                byte blockData = data.read(Type.BYTE, 4);

                BlockStorage blockStorage = session.getUserData().getProtocolStorage().get(BlockStorage.class);

                if (blockStorage != null) {
                    blockStorage.setBlockAt(x >> 4, z >> 4, x, y, z, blockId);

                    if (blockId == 54) {
                        blockData = RotationUtil.fixBlockRotation(session, x, y, z);
                    }
                }

                return PacketUtil.createPacket(0x35, new TypeHolder[]{
                        data.read(0),
                        data.read(1),
                        data.read(2),
                        data.read(3),
                        set(Type.BYTE, blockData),
                });
            }
        });

        // unload chunk
        addTranslator(0x32, PacketDirection.SERVER_TO_CLIENT, new PacketTranslator() {

            @Override
            public PacketData translate(ServerSession session, PacketData data) {
                BlockStorage blockStorage = session.getUserData().getProtocolStorage().get(BlockStorage.class);
                if (blockStorage != null) {
                    byte mode = data.read(Type.BYTE, 2);

                    if (mode == 0) {
                        int chunkX = data.read(Type.INT, 0);
                        int chunkZ = data.read(Type.INT, 1);

                        blockStorage.removeChunk(chunkX, chunkZ);
                    }
                }

                return data;
            }
        });

        // chunk data
        addTranslator(0x33, PacketDirection.SERVER_TO_CLIENT, new PacketTranslator() {

            @Override
            public PacketData translate(ServerSession session, PacketData data) {
                V1_3BChunk chunk = data.read(Type.V1_3B_CHUNK, 0);
                int chunkX = chunk.getX() >> 4;
                int chunkZ = chunk.getZ() >> 4;

                // skip non-full chunk updates
                if (chunk.getXSize() * chunk.getYSize() * chunk.getZSize() != 32768) {
                    return data;
                }

                BlockStorage blockStorage = session.getUserData().getProtocolStorage().get(BlockStorage.class);
                if (blockStorage != null) {
                    List<BlockLocation> locationList = new ArrayList<>();
                    try {
                        byte[] chunkData = chunk.getChunk();

                        for (int x = 0; x < 16; x++) {
                            for (int y = 0; y < 128; y++) {
                                for (int z = 0; z < 16; z++) {
                                    int blockId = chunkData[getBlockIndexAt(x, y, z)];

                                    if (SolidBlockList.isSolid(blockId)) {

                                        if (blockId == 54) {
                                            locationList.add(new BlockLocation(x, y, z));
                                        }

                                        blockStorage.setBlockAt(chunkX, chunkZ, chunk.getX() + x, chunk.getY() + y, chunk.getZ() + z, blockId);
                                    }
                                }
                            }
                        }

                        for (BlockLocation location : locationList) {
                            int x = location.getX();
                            int y = location.getY();
                            int z = location.getZ();

                            byte rotation = RotationUtil.fixBlockRotation(session, chunk.getX() + x, chunk.getY() + y, chunk.getZ() + z);
                            int blockDataOffset = 32768;
                            int blockLightOffset = 65536;

                            setNibble(chunkData, x, y, z, rotation, blockDataOffset);
                            setNibble(chunkData, x, y, z, (byte) 15, blockLightOffset);
                        }

                        chunk.setChunk(chunkData);
                    } catch (ArrayIndexOutOfBoundsException ignored) {
                    }
                }

                return PacketUtil.createPacket(0x33, new TypeHolder[]{
                        set(Type.V1_3B_CHUNK, chunk)
                });
            }
        });
    }

    private void setNibble(byte[] data, int x, int y, int z, byte value, int offset) {
        int nibbleIndex = (x << 11 | z << 7 | y) >> 1;

        if ((nibbleIndex & 1) == 0) {
            data[nibbleIndex + offset] = (byte) (data[nibbleIndex + offset] & 240 | value & 15);
        } else {
            data[nibbleIndex + offset] = (byte) (data[nibbleIndex + offset] & 15 | (value & 15) << 4);
        }
    }

    private int getBlockIndexAt(int x, int y, int z) {
        return x << 11 | z << 7 | y;
    }

    private PacketData createTabEntryPacket(String username, boolean online) {

        return PacketUtil.createPacket(0xC9, new TypeHolder[]{
                set(Type.STRING, username),
                set(Type.BYTE, (byte) (online ? 1 : 0)),
                set(Type.SHORT, (short) 0)
        });
    }
}
