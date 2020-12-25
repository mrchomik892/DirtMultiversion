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

package com.github.dirtpowered.dirtmv.network.versions.Release47To5;

import com.github.dirtpowered.dirtmv.data.MinecraftVersion;
import com.github.dirtpowered.dirtmv.data.protocol.PacketData;
import com.github.dirtpowered.dirtmv.data.protocol.Type;
import com.github.dirtpowered.dirtmv.data.protocol.TypeHolder;
import com.github.dirtpowered.dirtmv.data.protocol.objects.BlockChangeRecord;
import com.github.dirtpowered.dirtmv.data.protocol.objects.BlockLocation;
import com.github.dirtpowered.dirtmv.data.protocol.objects.OptionalPosition;
import com.github.dirtpowered.dirtmv.data.protocol.objects.V1_2MultiBlockArray;
import com.github.dirtpowered.dirtmv.data.protocol.objects.profile.GameProfile;
import com.github.dirtpowered.dirtmv.data.protocol.objects.profile.Property;
import com.github.dirtpowered.dirtmv.data.protocol.objects.tablist.PlayerListEntry;
import com.github.dirtpowered.dirtmv.data.protocol.objects.tablist.TabListAction;
import com.github.dirtpowered.dirtmv.data.protocol.objects.tablist.TabListEntry;
import com.github.dirtpowered.dirtmv.data.translator.PacketDirection;
import com.github.dirtpowered.dirtmv.data.translator.PacketTranslator;
import com.github.dirtpowered.dirtmv.data.translator.ProtocolState;
import com.github.dirtpowered.dirtmv.data.translator.ServerProtocol;
import com.github.dirtpowered.dirtmv.data.user.ProtocolStorage;
import com.github.dirtpowered.dirtmv.data.utils.ChatUtils;
import com.github.dirtpowered.dirtmv.data.utils.PacketUtil;
import com.github.dirtpowered.dirtmv.network.server.ServerSession;
import com.github.dirtpowered.dirtmv.network.versions.Beta17To14.storage.BlockStorage;
import com.github.dirtpowered.dirtmv.network.versions.Release47To5.chunk.V1_3ToV1_8ChunkTranslator;
import com.github.dirtpowered.dirtmv.network.versions.Release47To5.entity.OnGroundTracker;
import com.github.dirtpowered.dirtmv.network.versions.Release47To5.inventory.QuickBarTracker;
import com.github.dirtpowered.dirtmv.network.versions.Release47To5.inventory.WindowTypeTracker;
import com.github.dirtpowered.dirtmv.network.versions.Release47To5.other.BlockMiningTimeFixer;
import com.github.dirtpowered.dirtmv.network.versions.Release47To5.other.HardnessTable;
import com.github.dirtpowered.dirtmv.network.versions.Release4To78.ping.ServerPing;
import com.google.common.base.Charsets;
import com.google.gson.Gson;

import java.io.ByteArrayInputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.UUID;

public class ProtocolRelease47To5 extends ServerProtocol {

    public ProtocolRelease47To5() {
        super(MinecraftVersion.R1_8, MinecraftVersion.R1_7_6);

        addGroup(new MovementPackets());
        addGroup(new InventoryPackets());
        addGroup(new EntityPackets());
    }

    @Override
    public void onConnect(ServerSession session) {
        ProtocolStorage storage = session.getUserData().getProtocolStorage();
        storage.set(OnGroundTracker.class, new OnGroundTracker());
        storage.set(WindowTypeTracker.class, new WindowTypeTracker());
        storage.set(QuickBarTracker.class, new QuickBarTracker());

        // fixes block hardness inconsistencies
        if (session.getMain().getConfiguration().getServerVersion() == MinecraftVersion.B1_7_3) {
            storage.set(BlockMiningTimeFixer.class, new BlockMiningTimeFixer(session));
        }
    }

    public static long toBlockPosition(int x, int y, int z) {
        return (((long) x & 0x3FFFFFF) << 38) | ((((long) y) & 0xFFF) << 26) | (((long) z) & 0x3FFFFFF);
    }

    private BlockLocation fromBlockPosition(long encodedPosition) {
        int x = (int) (encodedPosition >> 38);
        int y = (int) ((encodedPosition >> 26) & 4095);
        int z = (int) ((encodedPosition << 38) >> 38);

        return new BlockLocation(x, y, z);
    }

    @Override
    public void registerTranslators() {
        // status ping
        addTranslator(0x00, ProtocolState.STATUS, PacketDirection.SERVER_TO_CLIENT, new PacketTranslator() {

            @Override
            public PacketData translate(ServerSession session, PacketData data) {
                String json = data.read(Type.V1_7_STRING, 0);
                ServerPing serverPing = new Gson().fromJson(json, ServerPing.class);

                ServerPing.Version versionObj = new ServerPing.Version();
                versionObj.setName("1.8.x (unstable)");
                versionObj.setProtocol(47);

                serverPing.setVersion(versionObj);

                return PacketUtil.createPacket(0x00, new TypeHolder[]{
                        set(Type.V1_7_STRING, serverPing.toString())
                });
            }
        });

        // keep alive
        addTranslator(0x00, ProtocolState.PLAY, PacketDirection.SERVER_TO_CLIENT, new PacketTranslator() {

            @Override
            public PacketData translate(ServerSession session, PacketData data) {

                return PacketUtil.createPacket(0x00, new TypeHolder[]{
                        set(Type.VAR_INT, data.read(Type.INT, 0))
                });
            }
        });

        // join game
        addTranslator(0x01, ProtocolState.PLAY, PacketDirection.SERVER_TO_CLIENT, new PacketTranslator() {

            @Override
            public PacketData translate(ServerSession session, PacketData data) {

                return PacketUtil.createPacket(0x01, new TypeHolder[]{
                        data.read(0),
                        data.read(1),
                        data.read(2),
                        data.read(3),
                        data.read(4),
                        data.read(5),
                        set(Type.BOOLEAN, false)
                });
            }
        });

        // spawn position
        addTranslator(0x05, ProtocolState.PLAY, PacketDirection.SERVER_TO_CLIENT, new PacketTranslator() {

            @Override
            public PacketData translate(ServerSession session, PacketData data) {
                int x = data.read(Type.INT, 0);
                int y = data.read(Type.INT, 1);
                int z = data.read(Type.INT, 2);

                return PacketUtil.createPacket(0x05, new TypeHolder[]{
                        set(Type.LONG, toBlockPosition(x, y, z))
                });
            }
        });

        // update health
        addTranslator(0x06, ProtocolState.PLAY, PacketDirection.SERVER_TO_CLIENT, new PacketTranslator() {

            @Override
            public PacketData translate(ServerSession session, PacketData data) {

                return PacketUtil.createPacket(0x06, new TypeHolder[]{
                        data.read(0),
                        set(Type.VAR_INT, (int) data.read(Type.SHORT, 1)),
                        data.read(2)
                });
            }
        });

        // chat
        addTranslator(0x02, ProtocolState.PLAY, PacketDirection.SERVER_TO_CLIENT, new PacketTranslator() {

            @Override
            public PacketData translate(ServerSession session, PacketData data) {

                return PacketUtil.createPacket(0x02, new TypeHolder[]{
                        data.read(0),
                        set(Type.BYTE, (byte) 0),
                });
            }
        });

        // chunk data
        addTranslator(0x21, ProtocolState.PLAY, PacketDirection.SERVER_TO_CLIENT, new V1_3ToV1_8ChunkTranslator());

        // multi block change
        addTranslator(0x22, ProtocolState.PLAY, PacketDirection.SERVER_TO_CLIENT, new PacketTranslator() {

            @Override
            public PacketData translate(ServerSession session, PacketData data) {
                V1_2MultiBlockArray blockArray = data.read(Type.V1_2MULTIBLOCK_ARRAY, 2);
                DataInput dis = new DataInputStream(new ByteArrayInputStream(blockArray.getData()));

                BlockChangeRecord[] blockChangeRecords = new BlockChangeRecord[blockArray.getRecordCount()];

                for (int i = 0; i < blockArray.getRecordCount(); i++) {
                    try {
                        blockChangeRecords[i] = new BlockChangeRecord(dis.readShort(), dis.readShort());
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }

                return PacketUtil.createPacket(0x22, new TypeHolder[]{
                        data.read(0),
                        data.read(1),
                        set(Type.V1_8R_MULTIBLOCK_ARRAY, blockChangeRecords)
                });
            }
        });

        // chunk bulk
        addTranslator(0x26, -1, ProtocolState.PLAY, PacketDirection.SERVER_TO_CLIENT);

        // block change
        addTranslator(0x23, ProtocolState.PLAY, PacketDirection.SERVER_TO_CLIENT, new PacketTranslator() {

            @Override
            public PacketData translate(ServerSession session, PacketData data) {
                int x = data.read(Type.INT, 0);
                short y = data.read(Type.UNSIGNED_BYTE, 1);
                int z = data.read(Type.INT, 2);

                return PacketUtil.createPacket(0x23, new TypeHolder[]{
                        set(Type.LONG, toBlockPosition(x, y, z)),
                        set(Type.VAR_INT, data.read(Type.VAR_INT, 3) << 4 | (data.read(Type.UNSIGNED_BYTE, 4) & 15))
                });
            }
        });

        // block action
        addTranslator(0x24, ProtocolState.PLAY, PacketDirection.SERVER_TO_CLIENT, new PacketTranslator() {

            @Override
            public PacketData translate(ServerSession session, PacketData data) {
                int x = data.read(Type.INT, 0);
                short y = data.read(Type.SHORT, 1);
                int z = data.read(Type.INT, 2);

                return PacketUtil.createPacket(0x24, new TypeHolder[]{
                        set(Type.LONG, toBlockPosition(x, y, z)),
                        data.read(3),
                        data.read(4),
                        data.read(5)
                });
            }
        });

        // block break animation
        addTranslator(0x25, ProtocolState.PLAY, PacketDirection.SERVER_TO_CLIENT, new PacketTranslator() {

            @Override
            public PacketData translate(ServerSession session, PacketData data) {
                int x = data.read(Type.INT, 1);
                int y = data.read(Type.INT, 2);
                int z = data.read(Type.INT, 3);

                return PacketUtil.createPacket(0x25, new TypeHolder[]{
                        data.read(0),
                        set(Type.LONG, toBlockPosition(x, y, z)),
                        data.read(4)
                });
            }
        });

        // effect
        addTranslator(0x28, ProtocolState.PLAY, PacketDirection.SERVER_TO_CLIENT, new PacketTranslator() {

            @Override
            public PacketData translate(ServerSession session, PacketData data) {
                int x = data.read(Type.INT, 1);
                byte y = data.read(Type.BYTE, 2);
                int z = data.read(Type.INT, 3);

                return PacketUtil.createPacket(0x28, new TypeHolder[]{
                        data.read(0),
                        set(Type.LONG, toBlockPosition(x, y, z)),
                        data.read(4),
                        data.read(5)
                });
            }
        });

        // update sign
        addTranslator(0x33, ProtocolState.PLAY, PacketDirection.SERVER_TO_CLIENT, new PacketTranslator() {

            @Override
            public PacketData translate(ServerSession session, PacketData data) {
                int x = data.read(Type.INT, 0);
                short y = data.read(Type.SHORT, 1);
                int z = data.read(Type.INT, 2);

                String[] lines = new String[4];
                for (int i = 0; i < 4; i++) {
                    lines[i] = ChatUtils.legacyToJsonString(data.read(Type.V1_7_STRING, 3 + i));
                }

                return PacketUtil.createPacket(0x33, new TypeHolder[] {
                        set(Type.LONG, toBlockPosition(x, y, z)),
                        set(Type.V1_7_STRING, lines[0]),
                        set(Type.V1_7_STRING, lines[1]),
                        set(Type.V1_7_STRING, lines[2]),
                        set(Type.V1_7_STRING, lines[3]),
                });
            }
        });

        // sign editor
        addTranslator(0x36, ProtocolState.PLAY, PacketDirection.SERVER_TO_CLIENT, new PacketTranslator() {

            @Override
            public PacketData translate(ServerSession session, PacketData data) {
                int x = data.read(Type.INT, 0);
                int y = data.read(Type.INT, 1);
                int z = data.read(Type.INT, 2);

                return PacketUtil.createPacket(0x36, new TypeHolder[] {
                        set(Type.LONG, toBlockPosition(x, y, z))
                });
            }
        });

        // tab list item
        addTranslator(0x38, ProtocolState.PLAY, PacketDirection.SERVER_TO_CLIENT, new PacketTranslator() {

            @Override
            public PacketData translate(ServerSession session, PacketData data) {
                String u = data.read(Type.V1_7_STRING, 0);
                if (u == null) { // skip if server is updating ping or something
                    return new PacketData(-1);
                }

                String username = ChatUtils.stripColor(u);

                boolean online = data.read(Type.BOOLEAN, 1);

                UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(Charsets.UTF_8));

                if (online) {
                    TabListEntry tabAddListEntry = new TabListEntry(TabListAction.ADD_PLAYER, new PlayerListEntry[] {
                            new PlayerListEntry(new GameProfile(uuid, username), new Property[0], 0, 0, null)
                    });

                    return PacketUtil.createPacket(0x38, new TypeHolder[] {
                            set(Type.TAB_LIST_ENTRY, tabAddListEntry)
                    });
                } else {
                    TabListEntry tabRemoveListEntry = new TabListEntry(TabListAction.REMOVE_PLAYER, new PlayerListEntry[] {
                            new PlayerListEntry(new GameProfile(uuid, username))
                    });

                    return PacketUtil.createPacket(0x38, new TypeHolder[] {
                            set(Type.TAB_LIST_ENTRY, tabRemoveListEntry)
                    });
                }
            }
        });

        // set experience
        addTranslator(0x1F, ProtocolState.PLAY, PacketDirection.SERVER_TO_CLIENT, new PacketTranslator() {

            @Override
            public PacketData translate(ServerSession session, PacketData data) {

                return PacketUtil.createPacket(0x1F, new TypeHolder[]{
                        data.read(0),
                        set(Type.VAR_INT, data.read(Type.SHORT, 1).intValue()),
                        set(Type.VAR_INT, data.read(Type.SHORT, 2).intValue())
                });
            }
        });

        // use bed
        addTranslator(0x0A, ProtocolState.PLAY, PacketDirection.SERVER_TO_CLIENT, new PacketTranslator() {

            @Override
            public PacketData translate(ServerSession session, PacketData data) {
                int x = data.read(Type.INT, 1);
                byte y = data.read(Type.BYTE, 2);
                int z = data.read(Type.INT, 3);

                return PacketUtil.createPacket(0x0A, new TypeHolder[]{
                        set(Type.VAR_INT, data.read(Type.INT, 0)),
                        set(Type.LONG, toBlockPosition(x, y, z))
                });
            }
        });

        // collect item
        addTranslator(0x0D, ProtocolState.PLAY, PacketDirection.SERVER_TO_CLIENT, new PacketTranslator() {

            @Override
            public PacketData translate(ServerSession session, PacketData data) {

                return PacketUtil.createPacket(0x0D, new TypeHolder[]{
                        set(Type.VAR_INT, data.read(Type.INT, 0)),
                        set(Type.VAR_INT, data.read(Type.INT, 1))
                });
            }
        });

        // client packets

        // keep alive
        addTranslator(0x00, ProtocolState.PLAY, PacketDirection.CLIENT_TO_SERVER, new PacketTranslator() {

            @Override
            public PacketData translate(ServerSession session, PacketData data) {

                return PacketUtil.createPacket(0x00, new TypeHolder[]{
                        set(Type.INT, data.read(Type.VAR_INT, 0))
                });
            }
        });

        // use entity
        addTranslator(0x02, ProtocolState.PLAY, PacketDirection.CLIENT_TO_SERVER, new PacketTranslator() {

            @Override
            public PacketData translate(ServerSession session, PacketData data) {
                OptionalPosition optPos = data.read(Type.V1_8R_USE_ENTITY_OPTIONAL_POSITION, 1);
                int action = optPos.getAction();

                if (action == 2) {
                    return new PacketData(-1);
                }

                return PacketUtil.createPacket(0x02, new TypeHolder[] {
                        set(Type.INT, data.read(Type.VAR_INT, 0)),
                        set(Type.BYTE, (byte) action)
                });
            }
        });

        // player digging
        addTranslator(0x07, ProtocolState.PLAY, PacketDirection.CLIENT_TO_SERVER, new PacketTranslator() {

            @Override
            public PacketData translate(ServerSession session, PacketData data) {
                long encodedPosition = data.read(Type.LONG, 1);
                int action = data.read(Type.UNSIGNED_BYTE, 0);

                BlockLocation l = fromBlockPosition(encodedPosition);

                ProtocolStorage storage = session.getUserData().getProtocolStorage();
                if (storage.hasObject(BlockMiningTimeFixer.class) && storage.hasObject(BlockStorage.class)) {
                    BlockMiningTimeFixer blockMiningTimeFixer = storage.get(BlockMiningTimeFixer.class);
                    BlockStorage blockStorage = storage.get(BlockStorage.class);

                    assert blockMiningTimeFixer != null;
                    assert blockStorage != null;

                    switch (action) {
                        case 0: // start digging
                            blockMiningTimeFixer.onBlockStartBreaking(l);
                            break;
                        case 1: // cancel digging
                            blockMiningTimeFixer.onBlockCancelBreaking(l);
                            break;
                        case 2: // finish digging
                            if (HardnessTable.exist(blockStorage.getBlockAt(l.getX(), l.getY(), l.getZ()))) {
                                return new PacketData(-1);
                            }
                            break;
                    }
                }

                return PacketUtil.createPacket(0x07, new TypeHolder[]{
                        data.read(0),
                        set(Type.INT, l.getX()),
                        set(Type.UNSIGNED_BYTE, (short) l.getY()),
                        set(Type.INT, l.getZ()),
                        set(Type.UNSIGNED_BYTE, data.read(Type.BYTE, 2).shortValue())
                });
            }
        });

        // place block
        addTranslator(0x08, ProtocolState.PLAY, PacketDirection.CLIENT_TO_SERVER, new PacketTranslator() {

            @Override
            public PacketData translate(ServerSession session, PacketData data) {
                long encodedPosition = data.read(Type.LONG, 0);

                BlockLocation l = fromBlockPosition(encodedPosition);

                return PacketUtil.createPacket(0x08, new TypeHolder[]{
                        set(Type.INT, l.getX()),
                        set(Type.UNSIGNED_BYTE, (short) l.getY()),
                        set(Type.INT, l.getZ()),
                        data.read(1),
                        set(Type.V1_3R_ITEM, data.read(Type.V1_8R_ITEM, 2)),
                        data.read(3),
                        data.read(4),
                        data.read(5),
                });
            }
        });

        // animation
        addTranslator(0x0A, ProtocolState.PLAY, PacketDirection.CLIENT_TO_SERVER, new PacketTranslator() {

            @Override
            public PacketData translate(ServerSession session, PacketData data) {

                return PacketUtil.createPacket(0x0A, new TypeHolder[] {
                        set(Type.INT, 0),
                        set(Type.BYTE, (byte) 1)
                });
            }
        });

        // entity action
        addTranslator(0x0B, ProtocolState.PLAY, PacketDirection.CLIENT_TO_SERVER, new PacketTranslator() {

            @Override
            public PacketData translate(ServerSession session, PacketData data) {
                int action = data.read(Type.VAR_INT, 1);

                return PacketUtil.createPacket(0x0B, new TypeHolder[]{
                        set(Type.INT, data.read(Type.VAR_INT, 0)),
                        set(Type.BYTE, (byte) (action + 1)),
                        set(Type.INT, data.read(Type.VAR_INT, 2)),
                });
            }
        });

        // update sign
        addTranslator(0x12, ProtocolState.PLAY, PacketDirection.CLIENT_TO_SERVER, new PacketTranslator() {

            @Override
            public PacketData translate(ServerSession session, PacketData data) {
                long encodedPosition = data.read(Type.LONG, 0);
                BlockLocation l = fromBlockPosition(encodedPosition);

                String[] lines = new String[4];
                for (int i = 0; i < 4; i++) {
                    String msg = data.read(Type.V1_7_STRING, 1 + i);

                    msg = ChatUtils.createChatComponentFromInvalidJson(msg);
                    msg = ChatUtils.jsonToLegacy(msg);
                    lines[i] = msg;
                }

                return PacketUtil.createPacket(0x12, new TypeHolder[]{
                        set(Type.INT, l.getX()),
                        set(Type.SHORT, (short) l.getY()),
                        set(Type.INT, l.getZ()),
                        set(Type.V1_7_STRING, lines[0]),
                        set(Type.V1_7_STRING, lines[1]),
                        set(Type.V1_7_STRING, lines[2]),
                        set(Type.V1_7_STRING, lines[3]),
                });
            }
        });

        // client settings
        addTranslator(0x15, ProtocolState.PLAY, PacketDirection.CLIENT_TO_SERVER, new PacketTranslator() {

            @Override
            public PacketData translate(ServerSession session, PacketData data) {

                return PacketUtil.createPacket(0x15, new TypeHolder[]{
                        data.read(0),
                        data.read(1),
                        data.read(2),
                        data.read(3),
                        data.read(4),
                        set(Type.BOOLEAN, false)
                });
            }
        });

        // player input
        addTranslator(0x0C, ProtocolState.PLAY, PacketDirection.CLIENT_TO_SERVER, new PacketTranslator() {

            @Override
            public PacketData translate(ServerSession session, PacketData data) {
                byte status = data.read(Type.BYTE, 2);

                return PacketUtil.createPacket(0x0C, new TypeHolder[] {
                        data.read(0),
                        data.read(1),
                        set(Type.BOOLEAN, (status & 1) == 1),
                        set(Type.BOOLEAN, (status & 2) == 2)
                });
            }
        });

        // entity attributes
        addTranslator(0x20, -1, ProtocolState.PLAY, PacketDirection.SERVER_TO_CLIENT);

        // custom payload
        addTranslator(0x3F, -1, ProtocolState.PLAY, PacketDirection.SERVER_TO_CLIENT);
    }
}
