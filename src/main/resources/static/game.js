const config = {
    type: Phaser.AUTO,
    width: 800,
    height: 600,
    parent: 'phaser-example',
    physics: {
        default: 'arcade',
        arcade: {
            debug: false,
        },
    },
    scene: {
        preload: preload,
        create: create,
        update: update,
    }
};

const game = new Phaser.Game(config);

let stompClient;
let myPlayer;
let otherPlayers = {};
const roomId = '1'; // Static room ID for this example
const playerId = 'player-' + Math.random().toString(36).substr(2, 9);

function preload() {
    // No assets to load for this basic example
}

function create() {
    const scene = this;
    scene.cursors = scene.input.keyboard.createCursorKeys();

    // --- WebSocket Connection ---
    const socket = new SockJS('/ws-snowpeak');
    stompClient = new Stomp.Client({
        webSocketFactory: () => socket,
        debug: (str) => { console.log(new Date(), str); },
        reconnectDelay: 5000,
    });

    stompClient.onConnect = (frame) => {
        console.log('Connected: ' + frame);

        // Subscribe to the room topic
        stompClient.subscribe(`/topic/room.${roomId}`, onMessageReceived);

        // Create and send the initial state for this player
        const startX = Math.random() * (config.width - 100) + 50;
        const startY = Math.random() * (config.height - 100) + 50;

        myPlayer = createPlayer(scene, playerId, startX, startY, 0x00ff00); // My player is green

        const initialState = {
            playerId: playerId,
            x: myPlayer.x,
            y: myPlayer.y,
            direction: 'IDLE',
            animState: 'IDLE',
            role: 'SERVER',
            roomId: roomId
        };
        stompClient.publish({ destination: '/app/join', body: JSON.stringify(initialState) });
    };

    stompClient.onStompError = (frame) => {
        console.error('Broker reported error: ' + frame.headers['message']);
        console.error('Additional details: ' + frame.body);
    };

    stompClient.activate();
}

function update() {
    if (!myPlayer) return;

    let moveData = {
        playerId: playerId,
        x: myPlayer.x,
        y: myPlayer.y,
        direction: 'IDLE',
        animState: 'IDLE',
        role: 'SERVER',
        roomId: roomId
    };

    const speed = 200;
    myPlayer.body.setVelocity(0);

    if (this.cursors.left.isDown) {
        myPlayer.body.setVelocityX(-speed);
        moveData.animState = 'WALK';
        moveData.direction = 'LEFT';
    } else if (this.cursors.right.isDown) {
        myPlayer.body.setVelocityX(speed);
        moveData.animState = 'WALK';
        moveData.direction = 'RIGHT';
    }

    if (this.cursors.up.isDown) {
        myPlayer.body.setVelocityY(-speed);
        moveData.animState = 'WALK';
        moveData.direction = 'UP';
    } else if (this.cursors.down.isDown) {
        myPlayer.body.setVelocityY(-speed);
        moveData.animState = 'WALK';
        moveData.direction = 'DOWN';
    }
    
    myPlayer.body.velocity.normalize().scale(speed);


    // Only send an update if the player is moving
    if (moveData.animState === 'WALK') {
        moveData.x = myPlayer.x;
        moveData.y = myPlayer.y;
        stompClient.publish({ destination: `/app/move`, body: JSON.stringify(moveData) });
    }

    // --- Interpolation for other players ---
    for (const id in otherPlayers) {
        const player = otherPlayers[id];
        if (player.targetPos) {
            // Lerp position
            player.x = Phaser.Math.Linear(player.x, player.targetPos.x, 0.2);
            player.y = Phaser.Math.Linear(player.y, player.targetPos.y, 0.2);

            // Snap to target if very close to avoid endless tiny movements
            if (Phaser.Math.Distance.Between(player.x, player.y, player.targetPos.x, player.targetPos.y) < 0.5) {
                player.x = player.targetPos.x;
                player.y = player.targetPos.y;
                player.targetPos = null; // Stop interpolating
            }
        }
    }
}

function onMessageReceived(payload) {
    const message = JSON.parse(payload.body);

    if (message.playerId === playerId) {
        // This is a message about our own player, ignore it.
        return;
    }

    let playerSprite = otherPlayers[message.playerId];

    if (!playerSprite) {
        // --- Create new player sprite ---
        playerSprite = createPlayer(this, message.playerId, message.x, message.y, 0xff0000); // Other players are red
        otherPlayers[message.playerId] = playerSprite;
    }

    // --- Update target position for interpolation ---
    playerSprite.targetPos = { x: message.x, y: message.y };
    
    // We can also update animation state here if needed
    // playerSprite.anims.play(message.animState);
}

function createPlayer(scene, id, x, y, color) {
    const player = scene.physics.add.sprite(x, y);
    player.setCollideWorldBounds(true);
    
    // Instead of a sprite sheet, we'll draw a simple rectangle.
    const graphics = scene.add.graphics();
    graphics.fillStyle(color, 1);
    graphics.fillRect(-16, -16, 32, 32); // Draw a 32x32 square
    const texture = graphics.generateTexture(id, 32, 32);
    graphics.destroy(); // Clean up the graphics object

    player.setTexture(texture);
    return player;
}
