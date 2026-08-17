class DoomStreamProcessor extends AudioWorkletProcessor {
    constructor() {
        super();
        this.capacity = 65536;
        this.left = new Float32Array(this.capacity);
        this.right = new Float32Array(this.capacity);
        this.readIndex = 0;
        this.writeIndex = 0;
        this.available = 0;
        this.phase = 0;
        this.sourceRate = 11025;
        this.started = false;
        this.port.onmessage = event => this.receive(event.data);
    }

    receive(message) {
        if (message.type === "reset") {
            this.reset();
            return;
        }
        if (message.type !== "samples") return;
        if (message.sampleRate !== this.sourceRate) {
            this.reset();
            this.sourceRate = message.sampleRate;
        }
        const pcm = message.pcm;
        for (let sample = 0; sample + 1 < pcm.length; sample += 2) {
            if (this.available === this.capacity - 1) {
                this.readIndex = (this.readIndex + 1) % this.capacity;
                this.available--;
            }
            this.left[this.writeIndex] = pcm[sample] / 32768;
            this.right[this.writeIndex] = pcm[sample + 1] / 32768;
            this.writeIndex = (this.writeIndex + 1) % this.capacity;
            this.available++;
        }
    }

    reset() {
        this.readIndex = 0;
        this.writeIndex = 0;
        this.available = 0;
        this.phase = 0;
        this.started = false;
    }

    process(_inputs, outputs) {
        const output = outputs[0];
        if (!output?.length) return true;
        const outputLeft = output[0];
        const outputRight = output[1] ?? output[0];
        outputLeft.fill(0);
        outputRight.fill(0);

        const startupFrames = Math.ceil(this.sourceRate * 0.04);
        if (!this.started) {
            if (this.available < startupFrames) return true;
            this.started = true;
        }

        const step = this.sourceRate / sampleRate;
        for (let frame = 0; frame < outputLeft.length; frame++) {
            if (this.available < 2) {
                this.started = false;
                break;
            }
            const nextIndex = (this.readIndex + 1) % this.capacity;
            outputLeft[frame] =
                this.left[this.readIndex] +
                (this.left[nextIndex] - this.left[this.readIndex]) * this.phase;
            outputRight[frame] =
                this.right[this.readIndex] +
                (this.right[nextIndex] - this.right[this.readIndex]) * this.phase;

            this.phase += step;
            const consumed = Math.floor(this.phase);
            if (consumed > 0) {
                this.phase -= consumed;
                this.readIndex = (this.readIndex + consumed) % this.capacity;
                this.available = Math.max(0, this.available - consumed);
            }
        }
        return true;
    }
}

registerProcessor("doom-stream", DoomStreamProcessor);
