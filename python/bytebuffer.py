from struct import pack, unpack

__all__ = [
    'BYTE_ORDER_UNSIGNED_BIG_ENDIAN',
    'BYTE_ORDER_SIGNED_BIG_ENDIAN',
    'BYTE_ORDER_UNSIGNED_LITTLE_ENDIAN',
    'BYTE_ORDER_SIGNED_LITTLE_ENDIAN',
    'wrap',
    'allocate'
]

BYTE_ORDER_UNSIGNED_BIG_ENDIAN = 0
BYTE_ORDER_SIGNED_BIG_ENDIAN = 1
BYTE_ORDER_UNSIGNED_LITTLE_ENDIAN = 2
BYTE_ORDER_SIGNED_LITTLE_ENDIAN = 3

class _ByteBuffer(object):
    def __init__(self, buffer, pos=0, lim=None, mark=None):
        self._buffer = buffer
        self._buf_view = memoryview(buffer)
        self._capacity = len(buffer)
        self._mark = mark
        self._position = pos
        if not lim:
            self._limit = self._capacity
        else:
            self._limit = lim

        self._byte_order = BYTE_ORDER_UNSIGNED_BIG_ENDIAN
        self._short_fmt = '>H'
        self._int_fmt = '>I'
        self._long_fmt = '>L'
        self._float_fmt = '>f'
        self._double_fmt = '>d'

    # TODO: I could make getitem and setitem represent the underlying buffer.  So reads/writes would not
    # be absolute, they would be relative to the current position
    def __getitem__(self, key):
        return self._buf_view[key]

    def __setitem__(self, key, value):
        self._buf_view[key] = value

    def __len__(self):
        return self.remaining()

    @property
    def position(self):
        return self._position

    @position.setter
    def position(self, value):
        if value < 0 or value > self._capacity:
            raise IndexError("Position outside buffer boundries")
        else:
            self._position = value

    @property
    def limit(self):
        return self._limit

    @limit.setter
    def limit(self, value):
        if value < 0 or value > self._capacity:
            raise IndexError("Limit outside buffer boundries")
        else:
            self._limit = value

    @property
    def byte_order(self):
        return self._byte_order

    @byte_order.setter
    def byte_order(self, value):
        if value == BYTE_ORDER_UNSIGNED_BIG_ENDIAN:
            self._short_fmt = '>H'
            self._int_fmt = '>I'
            self._long_fmt = '>Q'
            self._float_fmt = '>f'
            self._double_fmt = '>d'
        elif value == BYTE_ORDER_SIGNED_BIG_ENDIAN:
            self._short_fmt = '>h'
            self._int_fmt = '>i'
            self._long_fmt = '>q'
            self._float_fmt = '>f'
            self._double_fmt = '>d'
        elif value == BYTE_ORDER_UNSIGNED_LITTLE_ENDIAN:
            self._short_fmt = '<H'
            self._int_fmt = '<I'
            self._long_fmt = '<Q'
            self._float_fmt = '<f'
            self._double_fmt = '<d'
        elif value == BYTE_ORDER_SIGNED_LITTLE_ENDIAN:
            self._short_fmt = '<h'
            self._int_fmt = '<i'
            self._long_fmt = '<q'
            self._float_fmt = '<f'
            self._double_fmt = '<d'
        else:
            raise ValueError("Invalid Byte Order")
        self._byte_order = value

    def clear(self):
        self._limit = self._capacity
        self._position = 0
        self._mark = None

    def flip(self):
        self._limit = self._position
        self._position = 0
        self._mark = None

    def rewind(self):
        self._position = 0
        self._mark = None

    def capacity(self):
        return self._capacity

    def remaining(self):
        return self._limit - self._position

    def hasRemaining(self):
        return self._limit > self._position

    def mark(self):
        self._mark = self._position

    def reset(self):
        if self._mark:
            self._position = self._mark

    def duplicate(self):
        new_buf = _ByteBuffer(self._buffer, self._position, self._limit, self._mark)
        new_buf.byte_order = self._byte_order
        return new_buf

    def array(self):
        return self._buffer

    def get(self, dest=None, offset=None, length=None, index=None):
        """
        ByteBuffer get method.

        Encompasses the overloaded java get functionality.
        The following invocations are valid:

        # Relative get method. Returns byte at current position as
        # integer in the range of 0 to 255. Position is incremented by 1
        buf.get()

        # Absolute get method. Returns byte as integer at index.  Position is not changed.
        buf.get(index=index)

        # Bulk get method. Reads number of bytes equal to the destination length.
        # Position is increased by length of destination array,
        # The ByteBuffer itself is returned.
        buf.get(dest=dest_byte_array)

        # Bulk get method. Reads 'len' number of bytes from the byte buffer.
        # The bytes are written into the destination bytearray starting at
        # the index offset. The position is increased by the number of bytes
        # retreived.
        # The ByteBuffer itself is returned
        buf.get(dest=dest_byte_array, offset=ofs, length=len)
        """
        if dest:
            # Bulk get method
            if not offset or not length:
                offset = 0
                length = len(dest)

            if length > self.remaining():
                raise ValueError("Length request greater than number of bytes remaining")
            else:
                end = self._position + length
                dest_end = offset + length
                dest[offset:dest_end] = self._buf_view[self._position:end]
                self._position = end
                return self
        else:
            if index:
                # Absolute get method
                return self._buf_view[index]
            else:
                # Relative get method
                if self.hasRemaining():
                    byte = self._buf_view[self._position]
                    self._position = self._position + 1
                    return byte
                else:
                    raise ValueError("No bytes remaining in this buffer")

    def _get_primitive(self, fmt, index, size):
        end = index + size
        if end <= self._limit and index >= 0:
            return unpack(fmt, self._buf_view[index:end])[0]
        else:
            raise BufferError("Transfer outside of buffer boundries")

    def getBytes(self, size, index=None):
        """
        Bulk get method to return a bytes object.  This functionality
        is not found the Java NIO bytebuffer, as java does not have
        an immutable bytes object (unless you consider String).

        This function differs from a bulk get() request in that
        instead of reading into a mutable bytearray provided by
        the user, an immutable bytes object is returned.  This isn't
        efficient for large transfers, but for small ones it is
        extremely useful.

        Byte Order does not matter for a bytes object, so it is
        left out of the format string
        """
        if index:
            end = index + size
            if end <= self._limit and index >= 0:
                return self._buf_view[index:end].tobytes()
            else:
                raise BufferError("Transfer outside of buffer boundries")
        else:
            end = self._position + size
            if end <= self._limit:
                value = self._buf_view[self._position:end].tobytes()
                self._position = end
                return value
            else:
                raise BufferError("Transfer outside of buffer boundries")


    def getShort(self, index=None):
        if index:
            return self._get_primitive(self._short_fmt, index, 2)
        else:
            value = self._get_primitive(self._short_fmt, self._position, 2)
            self._position = self._position + 2
            return value
        
    def getInt(self, index=None):
        if index:
            return self._get_primitive(self._int_fmt, index, 4)
        else:
            value = self._get_primitive(self._int_fmt, self._position, 4)
            self._position = self._position + 4
            return value

    def getLong(self, index=None):
        if index:
            return self._get_primitive(self._long_fmt, index, 8)
        else:
            value = self._get_primitive(self._long_fmt, self._position, 8)
            self._position = self._position + 8
            return value

    def getFloat(self, index=None):
        if index:
            return self._get_primitive(self._float_fmt, index, 4)
        else:
            value = self._get_primitive(self._float_fmt, self._position, 4)
            self._position = self._position + 4
            return value

    def getDouble(self, index=None):
        if index:
            return self._get_primitive(self._double_fmt, index, 8)
        else:
            value = self._get_primitive(self._double_fmt, self._position, 8)
            self._position = self._position + 8
            return value

    def fill(self, source):
        """
        Bytebuffer fill method.  This method is not offered in the
        java nio implementation.  It is similar to a bulk put()
        method, however it is acceptbale for the destination
        buffer to be smaller than the source buffer.  The all bytes
        from the source will be transferred into the destination
        until either the source is empty OR the destination is full.

        This method expects the source to be a ByteBuffer
        """
        if isinstance(source, _ByteBuffer):
            source_len = source.remaining()
            if source_len < self.remaining():
                transfer_len = source_len
            else:
                transfer_len = self.remaining()
            source_end = source.position + transfer_len
            dest_end = self._position + transfer_len
            self._buf_view[self._position:dest_end] = source._buf_view[source.position:source_end]
            source.position = source_end
            self._position = dest_end
            return self
        else:
            # TODO: implement this for bytearray as well
            pass

    def put(self, source, offset=None, length=None, index=None):
        """
        ByteBuffer put method.  Encompasses the overloaded java put methods.
        The following invocations are valid:

        # Relative put byte method.  Source is a byte represented by an integer value
        # with a range of 0 to 255. The byte is stored at current position in the buffer.
        # Position is incremented by 1.
        buf.put(byte)

        # Absolute put byte method. Source is a byte represented by an integer value
        # with a range of 0 to 255. The byte is stored at the supplied index.
        # The position is not changed.
        buf.put(byte, index=idx)

        # Bulk put method.  Source is a bytearray.  The entire contents of the
        # byte array are written to the buffer, and the position is increased
        # by the length of the source.
        buf.put(byte_array)

        # Bulk put method.  Source is a bytearray, with an offset and length
        # supplied.  The source array, starting at offset, ending at offset
        # plus length, are written to the buffer.  The position is increased
        # by the amount written.
        buf.put(byte_array, offset=ofs, length=len)

        # Bulk buffer copy.  Source is a ByteBuffer.  The remaining bytes in the
        # source buffer are written to the the current buffer.  Positions of
        # both buffers are increased by the number of bytes transferred.
        buf.put(source_byte_buffer)
        """
        if isinstance(source, _ByteBuffer):
            transfer_length = source.remaining()
            if transfer_length > self.remaining():
                raise BufferError("Source ByteBuffer has more bytes remaining than destination")
            source_end = source.position + transfer_length
            dest_end = self._position + transfer_length
            self._buf_view[self._position:dest_end] = source._buf_view[source.position:source_end]
            self._position = dest_end
            source.position = source_end
            return self
        elif isinstance(source, int):
            if source > 255 or source < 0:
                raise ValueError("Integer value should be an unsigned byte")
            if index:
                if index > 0  and index < self._limit:
                    self._buf_view[index] = source
                    return self
                else:
                    raise BufferError("Index outside of Buffer boundries")
            else:
                if self.hasRemaining():
                    self._buf_view[self._position] = source
                    self._position = self._position + 1
                    return self
                else:
                    raise BufferError("No space remaining in buffer")
        elif not length or not offset:
            length = len(source)
            offset = 0

        if length > self.remaining():
            raise BufferError("Not enough space remaining in buffer")
        else:
            end = self._position + length
            source_end = offset + length
            source_view = memoryview(source)
            self._buf_view[self._position:end] = source_view[offset:source_end]
            self._position = end
            return self

    def _put_primitive(self, src, fmt, index, length):
        end = index + length
        if end > self._limit or index < 0:
            raise BufferError("Transfer outside of buffer boundries")

        src_bytes = pack(fmt, src)
        self._buf_view[index:end] = src_bytes

    def putShort(self, source, index=None):
        if index:
            self._put_primitive(source, self._short_fmt, index, 2)
        else:
            self._put_primitive(source, self._short_fmt, self._position, 2)
            self._position = self._position + 2
        return self

    def putInt(self, source, index=None):
        if index:
            self._put_primitive(source, self._int_fmt, index, 4)
        else:
            self._put_primitive(source, self._int_fmt, self._position, 4)
            self._position = self._position + 4
        return self

    def putLong(self, source, index=None):
        if index:
            self._put_primitive(source, self._long_fmt, index, 8)
        else:
            self._put_primitive(source, self._long_fmt, self._position, 8)
            self._position = self._position + 8
        return self

    def putFloat(self, source, index=None):
        if index:
            self._put_primitive(source, self._float_fmt, index, 4)
        else:
            self._put_primitive(source, self._float_fmt, self._position, 4)
            self._position = self._position + 4
        return self

    def putDouble(self, source, index=None):
        if index:
            self._put_primitive(source, self._double_fmt, index, 8)
        else:
            self._put_primitive(source, self._double_fmt, self._position, 8)
            self._position = self._position + 8
        return self

    # TODO: get/put char, get/put double, get/put float

def wrap(buffer, offset=None, length=None):
    if offset and length:
        limit = offset + length
        return _ByteBuffer(buffer, pos=offset, lim=limit)
    else:
        return _ByteBuffer(buffer)

def allocate(capacity):
    buf = bytearray(capacity)
    return _ByteBuffer(buf)
